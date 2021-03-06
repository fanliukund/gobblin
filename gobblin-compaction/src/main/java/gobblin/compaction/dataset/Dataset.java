/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.compaction.dataset;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.Path;

import com.google.common.collect.Lists;

import lombok.extern.slf4j.Slf4j;

import gobblin.compaction.mapreduce.MRCompactor;
import gobblin.configuration.State;
import gobblin.dataset.FileSystemDataset;


/**
 * A class that represents a dataset whose data should be compacted.
 *
 * @author Ziyang Liu
 */
@Slf4j
public class Dataset implements Comparable<Dataset>, FileSystemDataset {

  public static final double DEFAULT_PRIORITY = 1.0;
  public static final double DEFAULT_PRIORITY_REDUCTION_FACTOR = 1.0 / 3.0;

  public enum DatasetState {

    // The data completeness of this dataset has not been verified.
    UNVERIFIED,

    // The data completeness of this dataset has been verified.
    VERIFIED,

    // The data completeness of this dataset has timed out. In this case it is configurable whether the
    // compactor should or should not compact this dataset.
    GIVEN_UP,

    // Compaction of this data set has been completed (which may have either succeeded or failed).
    COMPACTION_COMPLETE
  }

  public static class Builder {
    private Path inputPath;
    private Path inputLatePath;
    private Path outputPath;
    private Path outputLatePath;
    private Path outputTmpPath;
    private String datasetName;
    private double priority = DEFAULT_PRIORITY;

    @Deprecated
    private double lateDataThresholdForRecompact;

    public Builder withInputPath(Path inputPath) {
      this.inputPath = inputPath;
      return this;
    }

    public Builder withInputLatePath(Path inputLatePath) {
      this.inputLatePath = inputLatePath;
      return this;
    }

    public Builder withOutputPath(Path outputPath) {
      this.outputPath = outputPath;
      return this;
    }

    public Builder withOutputLatePath(Path outputLatePath) {
      this.outputLatePath = outputLatePath;
      return this;
    }

    public Builder withOutputTmpPath(Path outputTmpPath) {
      this.outputTmpPath = outputTmpPath;
      return this;
    }

    public Builder withDatasetName(String name) {
      this.datasetName = name;
      return this;
    }

    public Builder withPriority(double priority) {
      this.priority = priority;
      return this;
    }


    @Deprecated
    public Builder withLateDataThresholdForRecompact(double lateDataThresholdForRecompact) {
      this.lateDataThresholdForRecompact = lateDataThresholdForRecompact;
      return this;
    }

    public Dataset build() {
      return new Dataset(this);
    }
  }

  private final Path outputPath;
  private final Path outputLatePath;
  private final Path outputTmpPath;
  private final List<Path> additionalInputPaths;
  private final Collection<Throwable> throwables;

  private Path inputPath;
  private Path inputLatePath;
  private State jobProps;
  private double priority;
  private boolean needToRecompact;
  private final String datasetName;
  private AtomicReference<DatasetState> state;

  @Deprecated
  private double lateDataThresholdForRecompact;

  private Dataset(Builder builder) {
    this.inputPath = builder.inputPath;
    this.inputLatePath = builder.inputLatePath;
    this.outputPath = builder.outputPath;
    this.outputLatePath = builder.outputLatePath;
    this.outputTmpPath = builder.outputTmpPath;
    this.additionalInputPaths = Lists.newArrayList();
    this.throwables = Collections.synchronizedCollection(Lists.<Throwable> newArrayList());
    this.priority = builder.priority;
    this.lateDataThresholdForRecompact = builder.lateDataThresholdForRecompact;
    this.state = new AtomicReference<>(DatasetState.UNVERIFIED);
    this.datasetName = builder.datasetName;
    this.jobProps = new State();
  }

  /**
   * Input path that contains the data of this {@link Dataset} to be compacted.
   */
  public Path inputPath() {
    return this.inputPath;
  }

  /**
   * Path that contains the late data of this {@link Dataset} to be compacted.
   * Late input data may be generated if the input data is obtained from another compaction,
   * e.g., if we run hourly compaction and daily compaction on a topic where the compacted hourly
   * data is the input to the daily compaction.
   *
   * If this path contains any data and this {@link Dataset} is not already compacted, deduplication
   * will be applied to this {@link Dataset}.
   */
  public Path inputLatePath() {
    return this.inputLatePath;
  }

  /**
   * Output path for the compacted data.
   */
  public Path outputPath() {
    return this.outputPath;
  }

  /**
   * If {@link #outputPath()} is already compacted and new input data is found, those data can be copied
   * to this path.
   */
  public Path outputLatePath() {
    return this.outputLatePath;
  }

  /**
   * The path where the MR job writes output to. Data will be published to {@link #outputPath()} if the compaction
   * is successful.
   */
  public Path outputTmpPath() {
    return this.outputTmpPath;
  }

  public String getDatasetName() {
    return this.datasetName;
  }

  public boolean needToRecompact() {
    return this.needToRecompact;
  }

  /**
   * Additional paths of this {@link Dataset} besides {@link #inputPath()} that contain data to be compacted.
   */
  public List<Path> additionalInputPaths() {
    return this.additionalInputPaths;
  }

  /**
   * Add an additional input path for this {@link Dataset}.
   */
  public void addAdditionalInputPath(Path path) {
    this.additionalInputPaths.add(path);
  }

  /**
   * Add additional input paths for this {@link Dataset}.
   */
  public void addAdditionalInputPaths(Collection<Path> paths) {
    this.additionalInputPaths.addAll(paths);
  }

  public double priority() {
    return this.priority;
  }

  public DatasetState state() {
    return this.state.get();
  }

  /**
   * Reduce the priority of the dataset by {@link #DEFAULT_PRIORITY_REDUCTION_FACTOR}.
   * @return the reduced priority
   */
  public double reducePriority() {
    return reducePriority(DEFAULT_PRIORITY_REDUCTION_FACTOR);
  }

  /**
   * Reduce the priority of the dataset.
   * @param reductionFactor the reduction factor. The priority will be reduced by reductionFactor.
   * @return the reduced priority
   */
  public double reducePriority(double reductionFactor) {
    this.priority *= 1.0 - reductionFactor;
    return this.priority;
  }

  public void checkIfNeedToRecompact(DatasetHelper datasetHelper) {
    if (datasetHelper.getCondition().isRecompactionNeeded(datasetHelper)) {
      this.needToRecompact = true;
    }
  }

  @Deprecated
  public void checkIfNeedToRecompact(long lateDataCount, long nonLateDataCount) {
    double lateDataPercent = lateDataCount * 1.0 / (lateDataCount + nonLateDataCount);
    log.info("Late data percentage is " + lateDataPercent + " and threshold is " + this.lateDataThresholdForRecompact);
    if (lateDataPercent > this.lateDataThresholdForRecompact) {
      this.needToRecompact = true;
    }
  }

  public void setState(DatasetState state) {
    this.state.set(state);
  }

  /**
   * Sets the {@link DatasetState} of the {@link Dataset} to the given updated value if the
   * current value == the expected value.
   */
  public void compareAndSetState(DatasetState expect, DatasetState update) {
    this.state.compareAndSet(expect, update);
  }

  public State jobProps() {
    return this.jobProps;
  }

  public void setJobProps(State jobProps) {
    this.jobProps.addAll(jobProps);
  }

  public void setJobProp(String key, Object value) {
    this.jobProps.setProp(key, value);
  }

  public void setInputPath(Path newInputPath) {
    this.inputPath = newInputPath;
  }

  public void setInputLatePath(Path newInputLatePath) {
    this.inputLatePath = newInputLatePath;
  }

  public void resetNeedToRecompact() {
    this.needToRecompact = false;
  }

  private void cleanAdditionalInputPath () {
    this.additionalInputPaths.clear();
  }


  /**
   * Modify an existing dataset to recompact from its ouput path.
   */
  public void modifyDatasetForRecompact(State recompactState) {
    if (!this.jobProps().getPropAsBoolean(MRCompactor.COMPACTION_RECOMPACT_ALL_DATA, MRCompactor.DEFAULT_COMPACTION_RECOMPACT_ALL_DATA)) {
      this.setInputPath(this.outputLatePath);
      this.cleanAdditionalInputPath();
    } else {
      this.setInputPath(this.outputPath);
      this.setInputLatePath(this.outputLatePath);
      this.addAdditionalInputPath(this.outputLatePath);
    }

    this.setJobProps(recompactState);
    this.resetNeedToRecompact();
  }

  /**
   * Get dataset URN, which equals {@link #outputPath} by removing {@link MRCompactor#COMPACTION_JOB_DEST_PARTITION}
   * and {@link MRCompactor#COMPACTION_DEST_SUBDIR}, if any.
   */
  public String getUrn() {
    return this.simplifyOutputPath().toString();
  }

  /**
   * Get dataset name, which equals {@link Path#getName()} of {@link #outputPath} after removing
   * {@link MRCompactor#COMPACTION_JOB_DEST_PARTITION} and {@link MRCompactor#COMPACTION_DEST_SUBDIR}, if any.
   */
  public String getName() {
    return this.simplifyOutputPath().getName();
  }

  private Path simplifyOutputPath() {
    Path simplifiedPath = new Path(StringUtils.removeEnd(this.outputPath.toString(),
        this.jobProps().getProp(MRCompactor.COMPACTION_JOB_DEST_PARTITION, StringUtils.EMPTY)));
    simplifiedPath = new Path(StringUtils.removeEnd(simplifiedPath.toString(),
        this.jobProps().getProp(MRCompactor.COMPACTION_DEST_SUBDIR, MRCompactor.DEFAULT_COMPACTION_DEST_SUBDIR)));
    return simplifiedPath;
  }

  public Collection<Throwable> throwables() {
    return this.throwables;
  }

  /**
   * Record a {@link Throwable} in a {@link Dataset}.
   */
  public void addThrowable(Throwable t) {
    this.throwables.add(t);
  }

  /**
   * Skip the {@link Dataset} by setting its {@link DatasetState} to {@link DatasetState#COMPACTION_COMPLETE},
   * and record the given {@link Throwable} in the {@link Dataset}.
   */
  public void skip(Throwable t) {
    this.setState(DatasetState.COMPACTION_COMPLETE);
    this.throwables.add(t);
  }

  @Override
  public int compareTo(Dataset o) {
    return Double.compare(o.priority, this.priority);
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((this.inputPath == null) ? 0 : this.inputPath.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Dataset)) {
      return false;
    }
    Dataset other = (Dataset) obj;
    if (this.inputPath == null) {
      if (other.inputPath != null) {
        return false;
      }
    } else if (!this.inputPath.equals(other.inputPath)) {
      return false;
    }
    return true;
  }

  /**
   * @return the {@link Path} of the {@link Dataset}.
   */
  @Override
  public String toString() {
    return this.inputPath.toString();
  }

  @Override
  public Path datasetRoot() {
    return this.outputPath;
  }

  @Override
  public String datasetURN() {
    return this.datasetRoot().toString();
  }
}
