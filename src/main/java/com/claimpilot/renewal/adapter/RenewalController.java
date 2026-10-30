package com.claimpilot.renewal.adapter;

import com.claimpilot.idempotency.adapter.IdempotentResponses;
import com.claimpilot.renewal.application.RenewalRepository;
import com.claimpilot.renewal.application.RenewalRepository.Renewal;
import com.claimpilot.renewal.application.RenewalService;
import com.claimpilot.renewal.application.RenewalService.PayCommand;
import com.claimpilot.renewal.application.RenewalService.PaymentResult;
import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import com.claimpilot.shared.security.Role;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
class RenewalController {

    private final RenewalService renewals;
    private final RenewalRepository repo;
    private final JobLauncher launcher;
    private final JobRepository jobs;
    private final Job renewalJob;

    RenewalController(RenewalService renewals, RenewalRepository repo, JobLauncher launcher, JobRepository jobs,
                      Job renewalJob) {
        this.renewals = renewals;
        this.repo = repo;
        this.launcher = launcher;
        this.jobs = jobs;
        this.renewalJob = renewalJob;
    }

    record StepView(String name, String status, long read, long written, long commits) {
    }

    record RunView(long jobExecutionId, LocalDate date, Instant cutoff, String status, boolean replayed,
                   Instant startedAt, Instant endedAt, Long durationMs, long renewed, long paymentDue, long lapsed,
                   long reminders, List<StepView> steps) {
    }

    /**
     * Runs the renewal job for one business date, synchronously. The date is the job's identity: running a
     * completed date again returns that run (replayed), running a failed one restarts it where it stopped.
     */
    @PostMapping("/v1/renewals/run")
    ResponseEntity<RunView> run(Caller caller, @RequestParam LocalDate date) {
        caller.require(Role.MANAGER);
        JobParameters params = new JobParametersBuilder().addString("date", date.toString()).toJobParameters();
        JobExecution exec;
        boolean replayed = false;
        try {
            exec = launcher.run(renewalJob, params);
        } catch (JobInstanceAlreadyCompleteException e) {
            exec = jobs.getLastJobExecution(RenewalJobConfig.JOB, params);
            replayed = true;
        } catch (JobExecutionAlreadyRunningException e) {
            throw ApiException.conflict("renewal-running", "renewals for " + date + " are already running");
        } catch (JobRestartException e) {
            throw ApiException.conflict("renewal-not-restartable", e.getMessage());
        } catch (Exception e) {
            // two launches of a new date can collide while creating the job instance; the loser sees this
            JobExecution last = jobs.getLastJobExecution(RenewalJobConfig.JOB, params);
            if (last != null) {
                throw ApiException.conflict("renewal-running", "renewals for " + date + " were started concurrently");
            }
            throw new IllegalStateException("could not start the renewal job", e);
        }
        RunView view = view(exec, date, replayed);
        HttpStatus status = exec.getStatus() == BatchStatus.COMPLETED ? HttpStatus.OK
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(view);
    }

    private RunView view(JobExecution exec, LocalDate date, boolean replayed) {
        var steps = exec.getStepExecutions().stream()
                .sorted(java.util.Comparator.comparing(s -> s.getStepName()))
                .map(s -> new StepView(s.getStepName(), s.getStatus().name(), s.getReadCount(), s.getWriteCount(),
                        s.getCommitCount()))
                .toList();
        long lapsed = writes(exec, "lapse");
        long reminders = writes(exec, "remind");
        var counts = repo.countByExecution(exec.getId());
        Instant start = exec.getStartTime() == null ? null : exec.getStartTime().toInstant(ZoneOffset.UTC);
        Instant end = exec.getEndTime() == null ? null : exec.getEndTime().toInstant(ZoneOffset.UTC);
        Long ms = start == null || end == null ? null : Duration.between(start, end).toMillis();
        return new RunView(exec.getId(), date, RenewalService.cutoff(date), exec.getStatus().name(), replayed, start,
                end, ms, counts.get(RenewalRepository.Status.RENEWED),
                counts.get(RenewalRepository.Status.PAYMENT_FAILED), lapsed, reminders, steps);
    }

    private static long writes(JobExecution exec, String step) {
        return exec.getStepExecutions().stream().filter(s -> s.getStepName().equals(step))
                .mapToLong(s -> s.getWriteCount()).sum();
    }

    @PostMapping("/v1/policies/{id}/payments")
    ResponseEntity<PaymentResult> pay(Caller caller, @PathVariable UUID id,
                                      @RequestHeader(name = IdempotentResponses.KEY_HEADER, required = false) String key,
                                      @RequestBody PayCommand cmd) {
        return IdempotentResponses.of(renewals.pay(caller, id, key, cmd), HttpStatus.OK);
    }

    @GetMapping("/v1/policies/{id}/renewals")
    List<Renewal> history(Caller caller, @PathVariable UUID id) {
        return renewals.history(caller, id);
    }
}
