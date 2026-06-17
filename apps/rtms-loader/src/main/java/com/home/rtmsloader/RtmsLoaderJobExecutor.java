package com.home.rtmsloader;

public interface RtmsLoaderJobExecutor {

	RtmsLoaderJobExecution execute(RtmsLoaderJobPlan plan);
}
