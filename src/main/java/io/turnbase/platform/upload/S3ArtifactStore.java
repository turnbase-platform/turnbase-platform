package io.turnbase.platform.upload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** {@link ArtifactStore} over the raw {@code S3Client} + {@code S3Presigner} beans. */
@Component
class S3ArtifactStore implements ArtifactStore {

  private final S3Client s3Client;
  private final S3Presigner presigner;
  private final ArtifactStoreProperties properties;

  S3ArtifactStore(S3Client s3Client, S3Presigner presigner, ArtifactStoreProperties properties) {
    this.s3Client = s3Client;
    this.presigner = presigner;
    this.properties = properties;
  }

  @Override
  public PresignedUpload presignPut(String key, Instant expiresAt) {
    Duration ttl = Duration.between(Instant.now(), expiresAt);
    PutObjectRequest putRequest =
        PutObjectRequest.builder().bucket(properties.bucket()).key(key).build();
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .putObjectRequest(putRequest)
            .build();
    URI url = URI.create(presigner.presignPutObject(presignRequest).url().toString());
    return new PresignedUpload(url, expiresAt);
  }

  @Override
  public Optional<Long> sizeOf(String key) {
    try {
      var head =
          s3Client.headObject(
              HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
      return Optional.of(head.contentLength());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }

  @Override
  public Path downloadToTempFile(String key) {
    try {
      Path temp = Files.createTempFile("turnbase-bundle-", ".zip");
      s3Client.getObject(
          GetObjectRequest.builder().bucket(properties.bucket()).key(key).build(), temp);
      return temp;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(key)
            .contentType(contentType)
            .build(),
        RequestBody.fromBytes(content));
  }

  @Override
  public void putFile(String key, Path file, String contentType) {
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(key)
            .contentType(contentType)
            .build(),
        RequestBody.fromFile(file));
  }

  @Override
  public void delete(String key) {
    s3Client.deleteObject(
        DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
  }

  @Override
  public void deletePrefix(String prefix) {
    List<String> keys = listKeys(prefix);
    if (keys.isEmpty()) {
      return;
    }
    List<ObjectIdentifier> ids =
        keys.stream().map(k -> ObjectIdentifier.builder().key(k).build()).toList();
    // S3 DeleteObjects caps a single request at 1000 keys; the unpacked tree of one bundle stays
    // well under §2.3's 20 000-entry ceiling, but batch defensively rather than assume.
    for (int i = 0; i < ids.size(); i += 1000) {
      List<ObjectIdentifier> batch = ids.subList(i, Math.min(i + 1000, ids.size()));
      s3Client.deleteObjects(
          DeleteObjectsRequest.builder()
              .bucket(properties.bucket())
              .delete(Delete.builder().objects(batch).build())
              .build());
    }
  }

  @Override
  public List<String> listKeys(String prefix) {
    List<String> keys = new ArrayList<>();
    String continuationToken = null;
    do {
      ListObjectsV2Request.Builder requestBuilder =
          ListObjectsV2Request.builder().bucket(properties.bucket()).prefix(prefix);
      if (continuationToken != null) {
        requestBuilder.continuationToken(continuationToken);
      }
      ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
      response.contents().forEach(o -> keys.add(o.key()));
      continuationToken =
          Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
    } while (continuationToken != null);
    return keys;
  }
}
