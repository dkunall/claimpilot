package com.claimpilot.document.adapter;

import com.claimpilot.document.application.ObjectStore;
import com.claimpilot.shared.config.ClaimPilotProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * S3 adapter, tested against RustFS. Two endpoints: the internal one the service talks to, and the public one
 * that presigned URLs point at (inside compose they differ). Path-style, since local stores lack virtual hosts.
 */
class S3ObjectStore implements ObjectStore, AutoCloseable {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private volatile boolean bucketReady;

    S3ObjectStore(ClaimPilotProperties.Storage cfg) {
        this.bucket = cfg.bucket();
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey()));
        var service = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(cfg.endpoint()))
                .region(Region.of(cfg.region()))
                .credentialsProvider(creds)
                .serviceConfiguration(service)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(cfg.publicEndpoint()))
                .region(Region.of(cfg.region()))
                .credentialsProvider(creds)
                .serviceConfiguration(service)
                .build();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        ensureBucket();
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .contentLength((long) content.length).build(), RequestBody.fromBytes(content));
    }

    @Override
    public Presigned presignPut(String key, String contentType, long sizeBytes, Duration ttl) {
        ensureBucket();
        var p = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(ttl)
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                        .contentLength(sizeBytes).build())
                .build());
        var headers = new LinkedHashMap<String, List<String>>();
        p.signedHeaders().forEach((k, v) -> {
            if (!k.equalsIgnoreCase("host")) {
                headers.put(k, v);
            }
        });
        return new Presigned(URI.create(p.url().toString()), headers, p.expiration());
    }

    @Override
    public Presigned presignGet(String key, Duration ttl) {
        var p = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()).build());
        return new Presigned(URI.create(p.url().toString()), java.util.Map.of(), p.expiration());
    }

    @Override
    public Optional<Long> sizeOf(String key) {
        try {
            return Optional.of(s3.headObject(r -> r.bucket(bucket).key(key)).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            s3.headBucket(r -> r.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(r -> r.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            s3.createBucket(r -> r.bucket(bucket));
        }
        bucketReady = true;
    }

    @Override
    public void close() {
        s3.close();
        presigner.close();
    }
}
