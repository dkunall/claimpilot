package com.claimpilot.renewal.adapter;

import com.claimpilot.renewal.application.RenewalService;
import com.claimpilot.shared.config.ClaimPilotProperties;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The nightly renewal job: lapse expired grace periods, renew due terms in parallel partitions, queue reminders.
 *
 * <p>Partitions are contiguous ranges of {@code policy.seq}, so each worker reads its own slice with keyset
 * paging and a restart resumes each failed partition after its last committed chunk. The worker step runs its
 * chunks under a resourceless transaction manager on purpose: each item commits its own business transaction
 * (the payment gateway is called between the read and the write), and the items are idempotent, so re-running
 * the tail of a chunk after a crash is harmless.
 */
@Configuration
class RenewalJobConfig {

    static final String JOB = "renewals";

    record Due(UUID id, long seq) {
    }

    @Bean
    Job renewalJob(JobRepository repo, Step lapseStep, Step renewStep, Step reminderStep) {
        return new JobBuilder(JOB, repo).start(lapseStep).next(renewStep).next(reminderStep).build();
    }

    @Bean
    Step lapseStep(JobRepository repo, RenewalService renewals) {
        return new StepBuilder("lapse", repo).tasklet((contribution, chunk) -> {
            LocalDate date = LocalDate.parse((String) chunk.getStepContext().getJobParameters().get("date"));
            contribution.incrementWriteCount(renewals.lapse(RenewalService.cutoff(date)));
            return RepeatStatus.FINISHED;
        }, new ResourcelessTransactionManager()).build();
    }

    @Bean
    Step reminderStep(JobRepository repo, RenewalService renewals) {
        return new StepBuilder("remind", repo).tasklet((contribution, chunk) -> {
            LocalDate date = LocalDate.parse((String) chunk.getStepContext().getJobParameters().get("date"));
            contribution.incrementWriteCount(renewals.remind(RenewalService.cutoff(date)));
            return RepeatStatus.FINISHED;
        }, new ResourcelessTransactionManager()).build();
    }

    @Bean
    Step renewStep(JobRepository repo, Step renewWorker, Partitioner seqPartitioner, ClaimPilotProperties props) {
        int grid = props.renewals().gridSize();
        var executor = new SimpleAsyncTaskExecutor("renewal-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(grid);
        return new StepBuilder("renew", repo).partitioner("renewWorker", seqPartitioner).step(renewWorker)
                .gridSize(grid).taskExecutor(executor).build();
    }

    /** Splits [min(seq), max(seq)] into {@code gridSize} contiguous ranges. */
    @Bean
    Partitioner seqPartitioner(JdbcClient db) {
        return gridSize -> {
            var bounds = db.sql("select coalesce(min(seq), 0), coalesce(max(seq), 0) from policy")
                    .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
            long min = bounds[0];
            long max = bounds[1];
            long span = Math.max(1, (max - min + gridSize) / gridSize);
            Map<String, ExecutionContext> parts = new HashMap<>();
            for (int i = 0; i < gridSize; i++) {
                long from = min + i * span;
                long to = i == gridSize - 1 ? Math.max(max, from) : from + span - 1;
                var ctx = new ExecutionContext();
                ctx.putLong("minSeq", from);
                ctx.putLong("maxSeq", to);
                parts.put("p" + i, ctx);
            }
            return parts;
        };
    }

    @Bean
    Step renewWorker(JobRepository repo, JdbcPagingItemReader<Due> dueReader, ItemWriter<Due> renewWriter,
                     ClaimPilotProperties props) {
        return new StepBuilder("renewWorker", repo)
                .<Due, Due>chunk(props.renewals().chunkSize(), new ResourcelessTransactionManager())
                .reader(dueReader).writer(renewWriter).build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<Due> dueReader(DataSource ds, ClaimPilotProperties props,
                                        @Value("#{jobParameters['date']}") String date,
                                        @Value("#{stepExecutionContext['minSeq']}") Long minSeq,
                                        @Value("#{stepExecutionContext['maxSeq']}") Long maxSeq) {
        Instant cutoff = RenewalService.cutoff(LocalDate.parse(date));
        return new JdbcPagingItemReaderBuilder<Due>()
                .name("dueReader")
                .dataSource(ds)
                .selectClause("select id, seq")
                .fromClause("from policy")
                .whereClause("where status = 'ACTIVE' and term_end <= :cutoff and term_end > :floor"
                        + " and seq between :min and :max")
                .parameterValues(Map.of("cutoff", Timestamp.from(cutoff),
                        "floor", Timestamp.from(cutoff.minus(RenewalService.TERM)), "min", minSeq, "max", maxSeq))
                .sortKeys(Map.of("seq", Order.ASCENDING))
                .rowMapper((rs, i) -> new Due(rs.getObject("id", UUID.class), rs.getLong("seq")))
                .pageSize(props.renewals().chunkSize())
                .build();
    }

    @Bean
    @StepScope
    ItemWriter<Due> renewWriter(RenewalService renewals, @Value("#{jobParameters['date']}") String date,
                                @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        Instant cutoff = RenewalService.cutoff(LocalDate.parse(date));
        return items -> {
            for (Due d : items) {
                renewals.renew(d.id(), cutoff, jobExecutionId);
            }
        };
    }
}
