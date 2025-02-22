/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.gc.iceberg.files;

import com.google.errorprone.annotations.MustBeClosed;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Spliterators.AbstractSpliterator;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.io.BulkDeletionFailureException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.ResolvingFileIO;
import org.immutables.value.Value;
import org.projectnessie.gc.files.DeleteResult;
import org.projectnessie.gc.files.DeleteSummary;
import org.projectnessie.gc.files.FileDeleter;
import org.projectnessie.gc.files.FileReference;
import org.projectnessie.gc.files.FilesLister;
import org.projectnessie.gc.files.NessieFileIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides functionality to {@link FilesLister list} and {@link FileDeleter delete} files using
 * Iceberg's {@link S3FileIO} for S3 schemes and/or {@link ResolvingFileIO} for non-S3 schemes.
 *
 * <p>The {@link FileIO} instances are only instantiated when needed.
 */
@Value.Immutable
public abstract class IcebergFiles implements FilesLister, FileDeleter, AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(IcebergFiles.class);

  public static Builder builder() {
    return ImmutableIcebergFiles.builder();
  }

  static Stream<String> filesAsStrings(Stream<FileReference> fileObjects) {
    return fileObjects.map(FileReference::absolutePath).map(URI::toString);
  }

  static URI ensureTrailingSlash(URI uri) {
    if (uri.getPath().endsWith("/")) {
      return uri;
    }
    return URI.create(uri + "/");
  }

  public interface Builder {
    Builder hadoopConfiguration(Configuration hadoopConfiguration);

    Builder putProperties(String key, String value);

    Builder putAllProperties(Map<String, ? extends String> entries);

    Builder properties(Map<String, ? extends String> entries);

    IcebergFiles build();
  }

  @Value.Default
  Configuration hadoopConfiguration() {
    return new Configuration();
  }

  abstract Map<String, String> properties();

  @SuppressWarnings("immutables:incompat")
  private volatile boolean hasResolvingFileIO;

  @SuppressWarnings("immutables:incompat")
  private volatile boolean hasS3FileIO;

  @Value.Lazy
  public FileIO resolvingFileIO() {
    ResolvingFileIO fileIO = new ResolvingFileIO();
    fileIO.initialize(properties());
    fileIO.setConf(hadoopConfiguration());
    hasResolvingFileIO = true;
    LOGGER.debug("Instantiated Iceberg's ResolvingFileIO");
    return fileIO;
  }

  @Value.Lazy
  S3FileIO s3() {
    S3FileIO fileIO = new S3FileIO();
    fileIO.initialize(properties());
    hasS3FileIO = true;
    LOGGER.debug("Instantiated Iceberg's S3FileIO");
    return fileIO;
  }

  @Override
  public void close() {
    try {
      if (hasS3FileIO) {
        s3().close();
      }
    } finally {
      if (hasResolvingFileIO) {
        resolvingFileIO().close();
      }
    }
  }

  private boolean isS3(URI uri) {
    switch (uri.getScheme()) {
      case "s3":
      case "s3a":
        return true;
      default:
        return false;
    }
  }

  @Override
  @MustBeClosed
  public Stream<FileReference> listRecursively(URI path) throws NessieFileIOException {
    URI basePath = ensureTrailingSlash(path);
    if (isS3(path)) {

      @SuppressWarnings("resource")
      S3FileIO fileIo = s3();
      return StreamSupport.stream(fileIo.listPrefix(basePath.toString()).spliterator(), false)
          .map(
              f ->
                  FileReference.of(
                      basePath.relativize(URI.create(f.location())),
                      basePath,
                      f.createdAtMillis()));
    }

    return listHadoop(basePath);
  }

  private Stream<FileReference> listHadoop(URI basePath) throws NessieFileIOException {
    Path p = new Path(basePath);
    FileSystem fs;
    try {
      fs = p.getFileSystem(hadoopConfiguration());
    } catch (IOException e) {
      throw new NessieFileIOException(e);
    }

    return StreamSupport.stream(
        new AbstractSpliterator<>(Long.MAX_VALUE, 0) {
          private RemoteIterator<LocatedFileStatus> iterator;

          @Override
          public boolean tryAdvance(Consumer<? super FileReference> action) {
            try {
              if (iterator == null) {
                iterator = fs.listFiles(p, true);
              }

              if (!iterator.hasNext()) {
                return false;
              }

              LocatedFileStatus status = iterator.next();

              if (status.isFile()) {
                action.accept(
                    FileReference.of(
                        basePath.relativize(status.getPath().toUri()),
                        basePath,
                        status.getModificationTime()));
              }

              return true;
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        },
        false);
  }

  @Override
  public DeleteResult delete(FileReference fileReference) {
    try {
      URI absolutePath = fileReference.absolutePath();
      @SuppressWarnings("resource")
      FileIO fileIO = isS3(absolutePath) ? s3() : resolvingFileIO();
      fileIO.deleteFile(absolutePath.toString());
      return DeleteResult.SUCCESS;
    } catch (Exception e) {
      LOGGER.debug("Failed to delete {}", fileReference, e);
      return DeleteResult.FAILURE;
    }
  }

  @Override
  public DeleteSummary deleteMultiple(URI baseUri, Stream<FileReference> fileObjects) {
    Stream<String> filesAsStrings = filesAsStrings(fileObjects);

    if (isS3(baseUri)) {
      return s3DeleteMultiple(filesAsStrings);
    }
    return hadoopDeleteMultiple(filesAsStrings);
  }

  private DeleteSummary s3DeleteMultiple(Stream<String> filesAsStrings) {
    @SuppressWarnings("resource")
    S3FileIO fileIo = s3();

    List<String> files = filesAsStrings.collect(Collectors.toList());
    long failed = 0L;
    try {
      fileIo.deleteFiles(files);
    } catch (BulkDeletionFailureException e) {
      failed = e.numberFailedObjects();
      LOGGER.debug("Failed to delete {} files (no further details available)", failed, e);
    }
    return DeleteSummary.of(files.size() - failed, failed);
  }

  private DeleteSummary hadoopDeleteMultiple(Stream<String> filesAsStrings) {
    @SuppressWarnings("resource")
    FileIO fileIo = resolvingFileIO();

    return filesAsStrings
        .map(
            f -> {
              try {
                fileIo.deleteFile(f);
                return DeleteResult.SUCCESS;
              } catch (Exception e) {
                LOGGER.debug("Failed to delete {}", f, e);
                return DeleteResult.FAILURE;
              }
            })
        .reduce(DeleteSummary.EMPTY, DeleteSummary::add, DeleteSummary::add);
  }
}
