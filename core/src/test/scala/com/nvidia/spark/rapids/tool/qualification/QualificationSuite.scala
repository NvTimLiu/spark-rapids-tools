/*
 * Copyright (c) 2021-2023, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids.tool.qualification

import java.io.File
import java.util.concurrent.TimeUnit.NANOSECONDS

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.io.Source

import com.nvidia.spark.rapids.BaseTestSuite
import com.nvidia.spark.rapids.tool.{EventLogPathProcessor, StatusReportCounts, ToolTestUtils}
import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.ml.feature.PCA
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.scheduler.{SparkListener, SparkListenerStageCompleted, SparkListenerTaskEnd}
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession, TrampolineUtil}
import org.apache.spark.sql.functions.{desc, hex, udf}
import org.apache.spark.sql.rapids.tool.{AppBase, AppFilterImpl, ToolUtils}
import org.apache.spark.sql.rapids.tool.qualification.{QualificationAppInfo, QualificationSummaryInfo, RunningQualificationEventProcessor}
import org.apache.spark.sql.rapids.tool.util.RapidsToolsConfUtil
import org.apache.spark.sql.types._

// drop the fields that won't go to DataFrame without encoders
case class TestQualificationSummary(
    appName: String,
    appId: String,
    recommendation: String,
    estimatedGpuSpeedup: Double,
    estimatedGpuDur: Double,
    estimatedGpuTimeSaved: Double,
    sqlDataframeDuration: Long,
    sqlDataframeTaskDuration: Long,
    appDuration: Long,
    gpuOpportunity: Long,
    executorCpuTimePercent: Double,
    failedSQLIds: String,
    readFileFormatAndTypesNotSupported: String,
    writeDataFormat: String,
    complexTypes: String,
    nestedComplexTypes: String,
    potentialProblems: String,
    longestSqlDuration: Long,
    nonSqlTaskDurationAndOverhead: Long,
    unsupportedSQLTaskDuration: Long,
    supportedSQLTaskDuration: Long,
    taskSpeedupFactor: Double,
    endDurationEstimated: Boolean,
    unsupportedExecs: String,
    unsupportedExprs: String,
    estimatedFrequency: Long)

class QualificationSuite extends BaseTestSuite {

  private val expRoot = ToolTestUtils.getTestResourceFile("QualificationExpectations")
  private val logDir = ToolTestUtils.getTestResourcePath("spark-events-qualification")

  private val csvDetailedFields = Seq(
    (QualOutputWriter.APP_NAME_STR, StringType),
    (QualOutputWriter.APP_ID_STR, StringType),
    (QualOutputWriter.SPEEDUP_BUCKET_STR, StringType),
    (QualOutputWriter.ESTIMATED_GPU_SPEEDUP, DoubleType),
    (QualOutputWriter.ESTIMATED_GPU_DURATION, DoubleType),
    (QualOutputWriter.ESTIMATED_GPU_TIMESAVED, DoubleType),
    (QualOutputWriter.SQL_DUR_STR, LongType),
    (QualOutputWriter.TASK_DUR_STR, LongType),
    (QualOutputWriter.APP_DUR_STR, LongType),
    (QualOutputWriter.GPU_OPPORTUNITY_STR, LongType),
    (QualOutputWriter.EXEC_CPU_PERCENT_STR, DoubleType),
    (QualOutputWriter.SQL_IDS_FAILURES_STR, StringType),
    (QualOutputWriter.READ_FILE_FORMAT_TYPES_STR, StringType),
    (QualOutputWriter.WRITE_DATA_FORMAT_STR, StringType),
    (QualOutputWriter.COMPLEX_TYPES_STR, StringType),
    (QualOutputWriter.NESTED_TYPES_STR, StringType),
    (QualOutputWriter.POT_PROBLEM_STR, StringType),
    (QualOutputWriter.LONGEST_SQL_DURATION_STR, LongType),
    (QualOutputWriter.NONSQL_DUR_STR, LongType),
    (QualOutputWriter.UNSUPPORTED_TASK_DURATION_STR, LongType),
    (QualOutputWriter.SUPPORTED_SQL_TASK_DURATION_STR, LongType),
    (QualOutputWriter.SPEEDUP_FACTOR_STR, DoubleType),
    (QualOutputWriter.APP_DUR_ESTIMATED_STR, BooleanType),
    (QualOutputWriter.UNSUPPORTED_EXECS, StringType),
    (QualOutputWriter.UNSUPPORTED_EXPRS, StringType),
    (QualOutputWriter.ESTIMATED_FREQUENCY, LongType))

  private val csvPerSQLFields = Seq(
    (QualOutputWriter.APP_NAME_STR, StringType),
    (QualOutputWriter.APP_ID_STR, StringType),
    (QualOutputWriter.SQL_ID_STR, StringType),
    (QualOutputWriter.SQL_DESC_STR, StringType),
    (QualOutputWriter.SQL_DUR_STR, LongType),
    (QualOutputWriter.GPU_OPPORTUNITY_STR, LongType),
    (QualOutputWriter.ESTIMATED_GPU_DURATION, DoubleType),
    (QualOutputWriter.ESTIMATED_GPU_SPEEDUP, DoubleType),
    (QualOutputWriter.ESTIMATED_GPU_TIMESAVED, DoubleType),
    (QualOutputWriter.SPEEDUP_BUCKET_STR, StringType))

  val schema = new StructType(csvDetailedFields.map(f => StructField(f._1, f._2, true)).toArray)
  val perSQLSchema = new StructType(csvPerSQLFields.map(f => StructField(f._1, f._2, true)).toArray)

  def csvDetailedHeader(ind: Int) = csvDetailedFields(ind)._1

  def readExpectedFile(expected: File, escape: String = "\\"): DataFrame = {
    ToolTestUtils.readExpectationCSV(sparkSession, expected.getPath(), Some(schema), escape)
  }

  def readPerSqlFile(expected: File, escape: String = "\\"): DataFrame = {
    ToolTestUtils.readExpectationCSV(sparkSession, expected.getPath(), Some(perSQLSchema), escape)
  }

  def readPerSqlTextFile(expected: File): Dataset[String] = {
    sparkSession.read.textFile(expected.getPath())
  }

  private def createSummaryForDF(
      appSums: Seq[QualificationSummaryInfo]): Seq[TestQualificationSummary] = {
    appSums.map { appInfoRec =>
      val sum = QualOutputWriter.createFormattedQualSummaryInfo(appInfoRec, ",")
      TestQualificationSummary(sum.appName, sum.appId, sum.recommendation,
        sum.estimatedGpuSpeedup, sum.estimatedGpuDur,
        sum.estimatedGpuTimeSaved, sum.sqlDataframeDuration,
        sum.sqlDataframeTaskDuration, sum.appDuration,
        sum.gpuOpportunity, sum.executorCpuTimePercent, sum.failedSQLIds,
        sum.readFileFormatAndTypesNotSupported, sum.writeDataFormat,
        sum.complexTypes, sum.nestedComplexTypes, sum.potentialProblems, sum.longestSqlDuration,
        sum.nonSqlTaskDurationAndOverhead,
        sum.unsupportedSQLTaskDuration, sum.supportedSQLTaskDuration, sum.taskSpeedupFactor,
        sum.endDurationEstimated, sum.unSupportedExecs, sum.unSupportedExprs,
        sum.estimatedFrequency)
    }
  }

  private def runQualificationTest(eventLogs: Array[String], expectFileName: String,
      shouldReturnEmpty: Boolean = false, expectPerSqlFileName: Option[String] = None,
      expectedStatus: Option[StatusReportCounts] = None) = {
    TrampolineUtil.withTempDir { outpath =>
      val resultExpectation = new File(expRoot, expectFileName)
      val outputArgs = Array(
        "--output-directory",
        outpath.getAbsolutePath())

      val allArgs = if (expectPerSqlFileName.isDefined) {
        outputArgs ++ Array("--per-sql")
      } else {
        outputArgs
      }

      val appArgs = new QualificationArgs(allArgs ++ eventLogs)
      val (exit, appSum) = QualificationMain.mainInternal(appArgs)
      assert(exit == 0)
      val spark2 = sparkSession
      import spark2.implicits._
      val summaryDF = createSummaryForDF(appSum).toDF
      val dfQual = sparkSession.createDataFrame(summaryDF.rdd, schema)

      // Default expectation for the status counts - All applications are successful.
      val expectedStatusCounts =
        expectedStatus.getOrElse(StatusReportCounts(appSum.length, 0, 0))
      // Compare the expected status counts with the actual status counts from the application
      ToolTestUtils.compareStatusReport(sparkSession, outpath.getAbsolutePath,
        expectedStatusCounts)

      if (shouldReturnEmpty) {
        assert(appSum.head.estimatedInfo.sqlDfDuration == 0.0)
      } else {
        val dfExpect = readExpectedFile(resultExpectation)
        assert(!dfQual.isEmpty)
        ToolTestUtils.compareDataFrames(dfQual, dfExpect)
        if (expectPerSqlFileName.isDefined) {
          val resultExpectation = new File(expRoot, expectPerSqlFileName.get)
          val dfPerSqlExpect = readPerSqlFile(resultExpectation)
          val actualExpectation = s"$outpath/rapids_4_spark_qualification_output/" +
            s"rapids_4_spark_qualification_output_persql.csv"
          val dfPerSqlActual = readPerSqlFile(new File(actualExpectation))
          ToolTestUtils.compareDataFrames(dfPerSqlActual, dfPerSqlExpect)
        }
      }
    }
  }

  test("RunningQualificationEventProcessor per sql") {
    TrampolineUtil.withTempDir { qualOutDir =>
      TrampolineUtil.withTempPath { outParquetFile =>
        TrampolineUtil.withTempPath { outJsonFile =>
          // note don't close the application here so we test running output
          ToolTestUtils.runAndCollect("running per sql") { spark =>
            val sparkConf = spark.sparkContext.getConf
            sparkConf.set("spark.rapids.qualification.output.numSQLQueriesPerFile", "2")
            sparkConf.set("spark.rapids.qualification.output.maxNumFiles", "3")
            sparkConf.set("spark.rapids.qualification.outputDir", qualOutDir.getPath)
            val listener = new RunningQualificationEventProcessor(sparkConf)
            spark.sparkContext.addSparkListener(listener)
            import spark.implicits._
            val testData = Seq((1, 2), (3, 4)).toDF("a", "b")
            testData.write.json(outJsonFile.getCanonicalPath)
            testData.write.parquet(outParquetFile.getCanonicalPath)
            val df = spark.read.parquet(outParquetFile.getCanonicalPath)
            val df2 = spark.read.json(outJsonFile.getCanonicalPath)
            // generate a bunch of SQL queries to test the file rolling, should run
            // 10 sql queries total with above and below
            for (i <- 1 to 7) {
              df.join(df2.select($"a" as "a2"), $"a" === $"a2").count()
            }
            val df3 = df.join(df2.select($"a" as "a2"), $"a" === $"a2")
            df3
          }
          // the code above that runs the Spark query stops the Sparksession
          // so create a new one to read in the csv file
          createSparkSession()
          val outputDir = qualOutDir.getPath + "/"
          val csvOutput0 = outputDir + QualOutputWriter.LOGFILE_NAME + "_persql_0.csv"
          val txtOutput0 = outputDir + QualOutputWriter.LOGFILE_NAME + "_persql_0.log"
          // check that there are 6 files since configured for 3 and have 1 csv and 1 log
          // file each
          val outputDirPath = new Path(outputDir)
          val fs = FileSystem.get(outputDirPath.toUri, RapidsToolsConfUtil.newHadoopConf())
          val allFiles = fs.listStatus(outputDirPath)
          assert(allFiles.size == 6)
          val dfPerSqlActual = readPerSqlFile(new File(csvOutput0))
          assert(dfPerSqlActual.columns.size == 10)
          val rows = dfPerSqlActual.collect()
          assert(rows.size == 2)
          val firstRow = rows(1)
          // , should be replaced with ;
          assert(firstRow(3).toString.contains("at QualificationSuite.scala"))

          // this reads everything into single column
          val dfPerSqlActualTxt = readPerSqlTextFile(new File(txtOutput0))
          assert(dfPerSqlActualTxt.columns.size == 1)
          val rowsTxt = dfPerSqlActualTxt.collect()
          // have to account for headers
          assert(rowsTxt.size == 6)
          val headerRowTxt = rowsTxt(1).toString
          assert(headerRowTxt.contains("Recommendation"))
          val firstValueRow = rowsTxt(3).toString
          assert(firstValueRow.contains("QualificationSuite.scala"))
        }
      }
    }
  }

  test("test order asc") {
    val logFiles = Array(
      s"$logDir/dataset_eventlog",
      s"$logDir/dsAndDf_eventlog.zstd",
      s"$logDir/udf_dataset_eventlog",
      s"$logDir/udf_func_eventlog"
    )
    TrampolineUtil.withTempDir { outpath =>
      val allArgs = Array(
        "--output-directory",
        outpath.getAbsolutePath(),
        "--order",
        "asc")

      val appArgs = new QualificationArgs(allArgs ++ logFiles)
      val (exit, appSum) = QualificationMain.mainInternal(appArgs)
      assert(exit == 0)
      assert(appSum.size == 4)
      assert(appSum.head.appId.equals("local-1622043423018"))

      // Default expectation for the status counts - All applications are successful.
      val expectedStatusCount = StatusReportCounts(appSum.length, 0, 0)
      // Compare the expected status counts with the actual status counts from the application
      ToolTestUtils.compareStatusReport(sparkSession, outpath.getAbsolutePath, expectedStatusCount)

      val filename = s"$outpath/rapids_4_spark_qualification_output/" +
        s"rapids_4_spark_qualification_output.log"
      val inputSource = Source.fromFile(filename)
      try {
        val lines = inputSource.getLines.toArray
        // 4 lines of header and footer
        assert(lines.size == (4 + 4))
        // skip the 3 header lines
        val firstRow = lines(3)
        assert(firstRow.contains("local-1623281204390"))
      } finally {
        inputSource.close()
      }
    }
  }

  test("test order desc") {
    val logFiles = Array(
      s"$logDir/dataset_eventlog",
      s"$logDir/dsAndDf_eventlog.zstd",
      s"$logDir/udf_dataset_eventlog",
      s"$logDir/udf_func_eventlog"
    )
    TrampolineUtil.withTempDir { outpath =>
      val allArgs = Array(
        "--output-directory",
        outpath.getAbsolutePath(),
        "--order",
        "desc",
        "--per-sql")

      val appArgs = new QualificationArgs(allArgs ++ logFiles)
      val (exit, appSum) = QualificationMain.mainInternal(appArgs)
      assert(exit == 0)
      assert(appSum.size == 4)
      assert(appSum.head.appId.equals("local-1622043423018"))

      val filename = s"$outpath/rapids_4_spark_qualification_output/" +
        s"rapids_4_spark_qualification_output.log"
      val inputSource = Source.fromFile(filename)
      try {
        val lines = inputSource.getLines.toArray
        // 4 lines of header and footer
        assert(lines.size == (4 + 4))
        // skip the 3 header lines
        val firstRow = lines(3)
        assert(firstRow.contains("local-1622043423018"))
      } finally {
        inputSource.close()
      }
      val persqlFileName = s"$outpath/rapids_4_spark_qualification_output/" +
        s"rapids_4_spark_qualification_output_persql.log"
      val persqlInputSource = Source.fromFile(persqlFileName)
      try {
        val lines = persqlInputSource.getLines.toArray
        // 4 lines of header and footer
        assert(lines.size == (4 + 17))
        // skip the 3 header lines
        val firstRow = lines(3)
        // this should be app + sqlID
        assert(firstRow.contains("local-1622043423018|     1"))
        assert(firstRow.contains("count at QualificationInfoUtils.scala:94"))
      } finally {
        persqlInputSource.close()
      }
    }
  }

  test("test limit desc") {
    val logFiles = Array(
      s"$logDir/dataset_eventlog",
      s"$logDir/dsAndDf_eventlog.zstd",
      s"$logDir/udf_dataset_eventlog",
      s"$logDir/udf_func_eventlog"
    )
    TrampolineUtil.withTempDir { outpath =>
      val allArgs = Array(
        "--output-directory",
        outpath.getAbsolutePath(),
        "--order",
        "desc",
        "-n",
        "2",
        "--per-sql")

      val appArgs = new QualificationArgs(allArgs ++ logFiles)
      val (exit, _) = QualificationMain.mainInternal(appArgs)
      assert(exit == 0)

      val filename = s"$outpath/rapids_4_spark_qualification_output/" +
        s"rapids_4_spark_qualification_output.log"
      val inputSource = Source.fromFile(filename)
      try {
        val lines = inputSource.getLines
        // 4 lines of header and footer, limit is 2
        assert(lines.size == (4 + 2))
      } finally {
        inputSource.close()
      }
      val persqlFileName = s"$outpath/rapids_4_spark_qualification_output/" +
        s"rapids_4_spark_qualification_output_persql.log"
      val persqlInputSource = Source.fromFile(persqlFileName)
      try {
        val lines = persqlInputSource.getLines
        // 4 lines of header and footer, limit is 2
        assert(lines.size == (4 + 2))
      } finally {
        persqlInputSource.close()
      }
    }
  }

  test("test datasource read format included") {
    val profileLogDir = ToolTestUtils.getTestResourcePath("spark-events-profiling")
    val logFiles = Array(s"$profileLogDir/eventlog_dsv1.zstd")
    TrampolineUtil.withTempDir { outpath =>
      val allArgs = Array(
        "--output-directory",
        outpath.getAbsolutePath(),
        "--report-read-schema")

      val appArgs = new QualificationArgs(allArgs ++ logFiles)
      val (exit, sum) = QualificationMain.mainInternal(appArgs)
      assert(exit == 0)

      val filename = s"$outpath/rapids_4_spark_qualification_output/" +
        s"rapids_4_spark_qualification_output.csv"
      val inputSource = Source.fromFile(filename)
      try {
        val lines = inputSource.getLines.toSeq
        // 1 for header, 1 for values
        assert(lines.size == 2)
        assert(lines.head.contains("Read Schema"))
        assert(lines(1).contains("loan399"))
      } finally {
        inputSource.close()
      }
    }
  }

  test("test skip gpu event logs") {
    val qualLogDir = ToolTestUtils.getTestResourcePath("spark-events-qualification")
    val logFiles = Array(s"$qualLogDir/gpu_eventlog")
    TrampolineUtil.withTempDir { outpath =>
      val allArgs = Array(
        "--output-directory",
        outpath.getAbsolutePath())

      val appArgs = new QualificationArgs(allArgs ++ logFiles)
      val (exit, appSum) = QualificationMain.mainInternal(appArgs)
      assert(exit == 0)
      assert(appSum.size == 0)

      // Application should fail. Status counts: 0 SUCCESS, 0 FAILURE, 1 UNKNOWN
      val expectedStatusCounts = StatusReportCounts(0, 0, 1)
      // Compare the expected status counts with the actual status counts from the application
      ToolTestUtils.compareStatusReport(sparkSession, outpath.getAbsolutePath,
        expectedStatusCounts)

      val filename = s"$outpath/rapids_4_spark_qualification_output/" +
        s"rapids_4_spark_qualification_output.csv"
      val inputSource = Source.fromFile(filename)
      try {
        val lines = inputSource.getLines.toSeq
        // 1 for header, Event log not parsed since it is from GPU run.
        assert(lines.size == 1)
      } finally {
        inputSource.close()
      }
    }
  }

  test("skip malformed json eventlog") {
    val profileLogDir = ToolTestUtils.getTestResourcePath("spark-events-profiling")
    val badEventLog = s"$profileLogDir/malformed_json_eventlog.zstd"
    val logFiles = Array(s"$logDir/nds_q86_test", badEventLog)
    // Status counts: 1 SUCCESS, 0 FAILURE, 1 UNKNOWN
    val expectedStatus = Some(StatusReportCounts(1, 0, 1))
    runQualificationTest(logFiles, "nds_q86_test_expectation.csv", expectedStatus = expectedStatus)
  }

  test("spark2 eventlog") {
    val profileLogDir = ToolTestUtils.getTestResourcePath("spark-events-profiling")
    val log = s"$profileLogDir/spark2-eventlog.zstd"
    runQualificationTest(Array(log), "spark2_expectation.csv")
  }

  test("test appName filter") {
    val appName = "Spark shell"
    val appArgs = new QualificationArgs(Array(
      "--application-name",
      appName,
      s"$logDir/rdd_only_eventlog",
      s"$logDir/empty_eventlog",
      s"$logDir/udf_func_eventlog"
    ))

    val (eventLogInfo, _) = EventLogPathProcessor.processAllPaths(
      appArgs.filterCriteria.toOption, appArgs.matchEventLogs.toOption, appArgs.eventlog(),
      RapidsToolsConfUtil.newHadoopConf())

    val appFilter = new AppFilterImpl(1000, RapidsToolsConfUtil.newHadoopConf(),
      Some(84000), 2)
    val result = appFilter.filterEventLogs(eventLogInfo, appArgs)
    assert(eventLogInfo.length == 3)
    assert(result.length == 2) // 2 out of 3 have "Spark shell" as appName.
  }

  test("test appName filter - Negation") {
    val appName = "~Spark shell"
    val appArgs = new QualificationArgs(Array(
      "--application-name",
      appName,
      s"$logDir/rdd_only_eventlog",
      s"$logDir/empty_eventlog",
      s"$logDir/udf_func_eventlog"
    ))

    val (eventLogInfo, _) = EventLogPathProcessor.processAllPaths(
      appArgs.filterCriteria.toOption, appArgs.matchEventLogs.toOption, appArgs.eventlog(),
      RapidsToolsConfUtil.newHadoopConf())

    val appFilter = new AppFilterImpl(1000, RapidsToolsConfUtil.newHadoopConf(),
      Some(84000), 2)
    val result = appFilter.filterEventLogs(eventLogInfo, appArgs)
    assert(eventLogInfo.length == 3)
    assert(result.length == 1) // 1 out of 3 does not has "Spark shell" as appName.
  }

  test("test udf event logs") {
    val logFiles = Array(
      s"$logDir/dataset_eventlog",
      s"$logDir/dsAndDf_eventlog.zstd",
      s"$logDir/udf_dataset_eventlog",
      s"$logDir/udf_func_eventlog"
    )
    runQualificationTest(logFiles, "qual_test_simple_expectation.csv",
      expectPerSqlFileName = Some("qual_test_simple_expectation_persql.csv"))
  }

  test("test missing sql end") {
    val logFiles = Array(s"$logDir/join_missing_sql_end")
    runQualificationTest(logFiles, "qual_test_missing_sql_end_expectation.csv")
  }

  test("test eventlog with no jobs") {
    val logFiles = Array(s"$logDir/empty_eventlog")
    runQualificationTest(logFiles, "", shouldReturnEmpty=true)
  }

  test("test eventlog with rdd only jobs") {
    val logFiles = Array(s"$logDir/rdd_only_eventlog")
    runQualificationTest(logFiles, "", shouldReturnEmpty=true)
  }

  test("test truncated log file 1") {
    val logFiles = Array(s"$logDir/truncated_eventlog")
    runQualificationTest(logFiles, "truncated_1_end_expectation.csv")
  }

  test("test nds q86 test") {
    val logFiles = Array(s"$logDir/nds_q86_test")
    runQualificationTest(logFiles, "nds_q86_test_expectation.csv",
      expectPerSqlFileName = Some("nds_q86_test_expectation_persql.csv"))
  }

  // event log rolling creates files under a directory
  test("test event log rolling") {
    val logFiles = Array(s"$logDir/eventlog_v2_local-1623876083964")
    runQualificationTest(logFiles, "directory_test_expectation.csv")
  }

  // these test files were setup to simulator the directory structure
  // when running on Databricks and are not really from there
  test("test db event log rolling") {
    val logFiles = Array(s"$logDir/db_sim_eventlog")
    runQualificationTest(logFiles, "db_sim_test_expectation.csv")
  }

  runConditionalTest("test nds q86 with failure test",
    shouldSkipFailedLogsForSpark) {
    val logFiles = Array(s"$logDir/nds_q86_fail_test")
    runQualificationTest(logFiles, "nds_q86_fail_test_expectation.csv",
      expectPerSqlFileName = Some("nds_q86_fail_test_expectation_persql.csv"))
  }

  test("test event log write format") {
    val logFiles = Array(s"$logDir/writeformat_eventlog")
    runQualificationTest(logFiles, "write_format_expectation.csv")
  }

  test("test event log nested types in ReadSchema") {
    val logFiles = Array(s"$logDir/nested_type_eventlog")
    runQualificationTest(logFiles, "nested_type_expectation.csv")
  }

  // this tests parseReadSchema by passing different schemas as strings. Schemas
  // with complex types, complex nested types, decimals and simple types
  test("test different types in ReadSchema") {
    val testSchemas: ArrayBuffer[ArrayBuffer[String]] = ArrayBuffer(
      ArrayBuffer(""),
      ArrayBuffer("firstName:string,lastName:string", "", "address:string"),
      ArrayBuffer("properties:map<string,string>"),
      ArrayBuffer("name:array<string>"),
      ArrayBuffer("name:string,booksInterested:array<struct<name:string,price:decimal(8,2)," +
          "author:string,pages:int>>,authbook:array<map<name:string,author:string>>, " +
          "pages:array<array<struct<name:string,pages:int>>>,name:string,subject:string"),
      ArrayBuffer("name:struct<fn:string,mn:array<string>,ln:string>," +
          "add:struct<cur:struct<st:string,city:string>," +
          "previous:struct<st:map<string,string>,city:string>>," +
          "next:struct<fn:string,ln:string>"),
      ArrayBuffer("name:map<id:int,map<fn:string,ln:string>>, " +
          "address:map<id:int,struct<st:string,city:string>>," +
          "orders:map<id:int,order:array<map<oname:string,oid:int>>>," +
          "status:map<name:string,active:string>")
    )

    var index = 0
    val expectedResult = List(
      ("", ""),
      ("", ""),
      ("map<string,string>", ""),
      ("array<string>", ""),
      ("array<struct<name:string,price:decimal(8,2),author:string,pages:int>>;" +
          "array<map<name:string,author:string>>;array<array<struct<name:string,pages:int>>>",
          "array<struct<name:string,price:decimal(8,2),author:string,pages:int>>;" +
              "array<map<name:string,author:string>>;array<array<struct<name:string,pages:int>>>"),
      ("struct<fn:string,mn:array<string>,ln:string>;" +
          "struct<cur:struct<st:string,city:string>,previous:struct<st:map<string,string>," +
          "city:string>>;struct<fn:string,ln:string>",
          "struct<fn:string,mn:array<string>,ln:string>;" +
              "struct<cur:struct<st:string,city:string>,previous:struct<st:map<string,string>," +
              "city:string>>"),
      ("map<id:int,map<fn:string,ln:string>>;map<id:int,struct<st:string,city:string>>;" +
          "map<id:int,order:array<map<oname:string,oid:int>>>;map<name:string,active:string>",
          "map<id:int,map<fn:string,ln:string>>;map<id:int,struct<st:string,city:string>>;" +
              "map<id:int,order:array<map<oname:string,oid:int>>>"))

    val result = testSchemas.map(x => AppBase.parseReadSchemaForNestedTypes(x))
    result.foreach { actualResult =>
      assert(ToolUtils.formatComplexTypes(actualResult._1).equals(expectedResult(index)._1))
      assert(ToolUtils.formatComplexTypes(actualResult._2).equals(expectedResult(index)._2))
      index += 1
    }
  }

  test("test jdbc problematic") {
    val logFiles = Array(s"$logDir/jdbc_eventlog.zstd")
    runQualificationTest(logFiles, "jdbc_expectation.csv")
  }

  private def createDecFile(spark: SparkSession, dir: String): Unit = {
    import spark.implicits._
    val dfGen = Seq("1.32").toDF("value")
      .selectExpr("CAST(value AS DECIMAL(4, 2)) AS value")
    dfGen.write.parquet(dir)
  }

  private def createIntFile(spark:SparkSession, dir:String): Unit = {
    import spark.implicits._
    val t1 = Seq((1, 2), (3, 4), (1, 6)).toDF("a", "b")
    t1.write.parquet(dir)
  }

  test("test generate udf same") {
    TrampolineUtil.withTempDir { outpath =>
      TrampolineUtil.withTempDir { eventLogDir =>
        val tmpParquet = s"$outpath/decparquet"
        createDecFile(sparkSession, tmpParquet)

        val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "dot") { spark =>
          val plusOne = udf((x: Int) => x + 1)
          import spark.implicits._
          spark.udf.register("plusOne", plusOne)
          val df = spark.read.parquet(tmpParquet)
          val df2 = df.withColumn("mult", $"value" * $"value")
          val df4 = df2.withColumn("udfcol", plusOne($"value"))
          df4
        }

        val allArgs = Array(
          "--output-directory",
          outpath.getAbsolutePath())
        val appArgs = new QualificationArgs(allArgs ++ Array(eventLog))
        val (exit, appSum) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
        assert(appSum.size == 1)
        val probApp = appSum.head
        assert(probApp.potentialProblems.contains("UDF"))
        assert(probApp.unsupportedSQLTaskDuration > 0) // only UDF is unsupported in the query.
      }
    }
  }

  test("test sparkML ") {
    TrampolineUtil.withTempDir { outpath =>
      TrampolineUtil.withTempDir { eventLogDir =>
        val tmpParquet = s"$outpath/mlOpsParquet"
        val sparkVersion = ToolUtils.sparkRuntimeVersion
        createDecFile(sparkSession, tmpParquet)
        val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "dot") { spark =>
          val data = Array(
            Vectors.sparse(5, Seq((1, 1.0), (3, 7.0))),
            Vectors.dense(2.0, 0.0, 3.0, 4.0, 5.0),
            Vectors.dense(4.0, 0.0, 0.0, 6.0, 7.0)
          )
          val df = spark.createDataFrame(data.map(Tuple1.apply)).toDF("features")
          val pca = new PCA()
            .setInputCol("features")
            .setOutputCol("pcaFeatures")
            .setK(3)
            .fit(df)
          df
        }

        val allArgs = Array(
          "--output-directory",
          outpath.getAbsolutePath(),
          "--ml-functions",
          "true")
        val appArgs = new QualificationArgs(allArgs ++ Array(eventLog))
        val (exit, appSum) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
        assert(appSum.size == 1)
        val mlOpsRes = appSum.head
        assert(mlOpsRes.mlFunctions.nonEmpty)
        // Spark3.2.+ generates a plan with 6 stages. StageID 3 and 4 are both
        // "isEmpty at RowMatrix.scala:441"
        val expStageCount = if (ToolUtils.isSpark320OrLater()) 6 else 5
        assert(mlOpsRes.mlFunctions.get.map(x=> x.stageId).size == expStageCount)
        assert(mlOpsRes.mlFunctions.get.head.mlOps.mkString.contains(
          "org.apache.spark.ml.feature.PCA.fit"))
        assert(mlOpsRes.mlFunctionsStageDurations.get.head.mlFuncName.equals("PCA"))
        // estimated GPU time is for ML function, there are no Spark Dataframe/SQL functions.
        assert(mlOpsRes.estimatedInfo.estimatedGpuTimeSaved > 0)
      }
    }
  }

  test("test xgboost") {
    val logFiles = Array(
      s"$logDir/xgboost_eventlog.zstd"
    )
    TrampolineUtil.withTempDir { outpath =>
      val allArgs = Array(
        "--output-directory",
        outpath.getAbsolutePath(),
        "--ml-functions",
        "true")

      val appArgs = new QualificationArgs(allArgs ++ logFiles)
      val (exit, appSum) = QualificationMain.mainInternal(appArgs)
      assert(exit == 0)
      assert(appSum.size == 1)
      val xgBoostRes = appSum.head
      assert(xgBoostRes.mlFunctions.nonEmpty)
      assert(xgBoostRes.mlFunctionsStageDurations.nonEmpty)
      assert(xgBoostRes.mlFunctions.get.head.mlOps.mkString.contains(
        "ml.dmlc.xgboost4j.scala.spark.XGBoostClassifier.train"))
      assert(xgBoostRes.mlFunctionsStageDurations.get.head.mlFuncName.equals("XGBoost"))
      assert(xgBoostRes.mlFunctionsStageDurations.get.head.duration == 46444)
    }
  }


  test("test with stage reuse") {
    TrampolineUtil.withTempDir { outpath =>
      TrampolineUtil.withTempDir { eventLogDir =>
        val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "dot") { spark =>
          import spark.implicits._
          val df = spark.sparkContext.makeRDD(1 to 1000, 6).toDF
          val df2 = spark.sparkContext.makeRDD(1 to 1000, 6).toDF
          val j1 = df.select( $"value" as "a")
            .join(df2.select($"value" as "b"), $"a" === $"b").cache()
          j1.count()
          j1.union(j1).count()
          // count above is important thing, here we just make up small df to return
          spark.sparkContext.makeRDD(1 to 2).toDF
        }

        val allArgs = Array(
          "--output-directory",
          outpath.getAbsolutePath())
        val appArgs = new QualificationArgs(allArgs ++ Array(eventLog))
        val (exit, appSum) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
        assert(appSum.size == 1)
        // note this would have failed an assert with total task time to small if we
        // didn't dedup stages
      }
    }
  }

  runConditionalTest("test generate udf different sql ops",
    checkUDFDetectionSupportForSpark) {
    TrampolineUtil.withTempDir { outpath =>

      TrampolineUtil.withTempDir { eventLogDir =>
        val tmpParquet = s"$outpath/decparquet"
        val grpParquet = s"$outpath/grpParquet"
        createDecFile(sparkSession, tmpParquet)
        createIntFile(sparkSession, grpParquet)
        val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "dot") { spark =>
          val plusOne = udf((x: Int) => x + 1)
          import spark.implicits._
          spark.udf.register("plusOne", plusOne)
          val df = spark.read.parquet(tmpParquet)
          val df2 = df.withColumn("mult", $"value" * $"value")
          // first run sql op with decimal only
          df2.collect()
          // run a separate sql op using just udf
          spark.sql("SELECT plusOne(5)").collect()
          // Then run another sql op that doesn't use with decimal or udf
          val t2 = spark.read.parquet(grpParquet)
          val res = t2.groupBy("a").max("b").orderBy(desc("a"))
          res
        }

        val allArgs = Array(
          "--output-directory",
          outpath.getAbsolutePath())
        val appArgs = new QualificationArgs(allArgs ++ Array(eventLog))
        val (exit, appSum) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
        assert(appSum.size == 1)
        val probApp = appSum.head
        assert(probApp.potentialProblems.contains("UDF"))
        assert(probApp.unsupportedSQLTaskDuration > 0) // only UDF is unsupported in the query.
      }
    }
  }

  test("test clusterTags when redacted") {
    TrampolineUtil.withTempDir { outpath =>
      TrampolineUtil.withTempDir { eventLogDir =>
        val tagConfs =
          Map("spark.databricks.clusterUsageTags.clusterAllTags" -> "*********(redacted)",
            "spark.databricks.clusterUsageTags.clusterId" -> "0617-131246-dray530",
            "spark.databricks.clusterUsageTags.clusterName" -> "job-215-run-34243234")
        val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "clustertagsRedacted",
          Some(tagConfs)) {
          spark =>
            import spark.implicits._

            val df1 = spark.sparkContext.makeRDD(1 to 1000, 6).toDF
            df1.sample(0.1)
        }
        val expectedClusterId = "0617-131246-dray530"
        val expectedJobId = "215"

        val allArgs = Array(
          "--output-directory",
          outpath.getAbsolutePath())
        val appArgs = new QualificationArgs(allArgs ++ Array(eventLog))
        val (exit, appSum) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
        assert(appSum.size == 1)
        val allTags = appSum.flatMap(_.allClusterTagsMap).toMap
        assert(allTags("ClusterId") == expectedClusterId)
        assert(allTags("JobId") == expectedJobId)
        assert(allTags.get("RunName") == None)
      }
    }
  }

  test("test clusterTags configs") {
    TrampolineUtil.withTempDir { outpath =>
      TrampolineUtil.withTempDir { eventLogDir =>

        val allTagsConfVal =
          """[{"key":"Vendor",
            |"value":"Databricks"},{"key":"Creator","value":"abc@company.com"},
            |{"key":"ClusterName","value":"job-215-run-1"},{"key":"ClusterId",
            |"value":"0617-131246-dray530"},{"key":"JobId","value":"215"},
            |{"key":"RunName","value":"test73longer"},{"key":"DatabricksEnvironment",
            |"value":"workerenv-7026851462233806"}]""".stripMargin
        val tagConfs =
          Map("spark.databricks.clusterUsageTags.clusterAllTags" -> allTagsConfVal)
        val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "clustertags",
          Some(tagConfs)) { spark =>
          import spark.implicits._

          val df1 = spark.sparkContext.makeRDD(1 to 1000, 6).toDF
          df1.sample(0.1)
        }
        val expectedClusterId = "0617-131246-dray530"
        val expectedJobId = "215"
        val expectedRunName = "test73longer"

        val allArgs = Array(
          "--output-directory",
          outpath.getAbsolutePath())
        val appArgs = new QualificationArgs(allArgs ++ Array(eventLog))
        val (exit, appSum) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
        assert(appSum.size == 1)
        val allTags = appSum.flatMap(_.allClusterTagsMap).toMap
        assert(allTags("ClusterId") == expectedClusterId)
        assert(allTags("JobId") == expectedJobId)
        assert(allTags("RunName") == expectedRunName)
      }
    }
  }

  test("test read datasource v1") {
    val profileLogDir = ToolTestUtils.getTestResourcePath("spark-events-profiling")
    val logFiles = Array(s"$profileLogDir/eventlog_dsv1.zstd")
    runQualificationTest(logFiles, "read_dsv1_expectation.csv")
  }

  test("test read datasource v2") {
    val profileLogDir = ToolTestUtils.getTestResourcePath("spark-events-profiling")
    val logFiles = Array(s"$profileLogDir/eventlog_dsv2.zstd")
    runQualificationTest(logFiles, "read_dsv2_expectation.csv")
  }

  test("test dsv1 complex") {
    val logFiles = Array(s"$logDir/complex_dec_eventlog.zstd")
    runQualificationTest(logFiles, "complex_dec_expectation.csv")
  }

  test("test dsv2 nested complex") {
    val logFiles = Array(s"$logDir/eventlog_nested_dsv2")
    runQualificationTest(logFiles, "nested_dsv2_expectation.csv")
  }

  test("sql metric agg") {
    TrampolineUtil.withTempDir { eventLogDir =>
      val listener = new ToolTestListener
      val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "sqlmetric") { spark =>
        spark.sparkContext.addSparkListener(listener)
        import spark.implicits._
        val testData = Seq((1, 2), (3, 4)).toDF("a", "b")
        spark.sparkContext.setJobDescription("testing, csv delimiter, replacement")
        testData.createOrReplaceTempView("t1")
        testData.createOrReplaceTempView("t2")
        spark.sql("SELECT a, MAX(b) FROM (SELECT t1.a, t2.b " +
          "FROM t1 JOIN t2 ON t1.a = t2.a) AS t " +
          "GROUP BY a ORDER BY a")
      }
      assert(listener.completedStages.length == 5)

      // run the qualification tool
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--per-sql",
          "--output-directory",
          outpath.getAbsolutePath,
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val persqlResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output_persql.csv"
        val dfPerSqlActual = readPerSqlFile(new File(persqlResults))
        // the number of columns actually won't be wrong if sql description is malformatted
        // because spark seems to drop extra column so need more checking
        assert(dfPerSqlActual.columns.size == 10)
        val rows = dfPerSqlActual.collect()
        assert(rows.size == 3)
        val firstRow = rows(1)
        // , should not be replaced with ; or any other delim
        assert(firstRow(3) == "testing, csv delimiter, replacement")

        // parse results from listener
        val executorCpuTime = listener.executorCpuTime
        val executorRunTime = listener.completedStages
          .map(_.stageInfo.taskMetrics.executorRunTime).sum

        val listenerCpuTimePercent =
          ToolUtils.calculateDurationPercent(executorCpuTime, executorRunTime)

        // compare metrics from event log with metrics from listener
        assert(sumInfo.head.executorCpuTimePercent === listenerCpuTimePercent)
      }
    }
  }

  test("running qualification print unsupported Execs and Exprs") {
    TrampolineUtil.withTempDir { eventLogDir =>
      val qualApp = new RunningQualificationApp()
      val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "streaming") { spark =>
        val listener = qualApp.getEventListener
        spark.sparkContext.addSparkListener(listener)
        import spark.implicits._
        val df1 = spark.sparkContext.parallelize(List(10, 20, 30, 40)).toDF
        df1.filter(hex($"value") === "A") // hex is not supported in GPU yet.
      }
      //stdout output tests
      val sumOut = qualApp.getSummary()
      val detailedOut = qualApp.getDetailed()
      assert(sumOut.nonEmpty)
      assert(sumOut.startsWith("|") && sumOut.endsWith("|\n"))
      assert(detailedOut.nonEmpty)
      assert(detailedOut.startsWith("|") && detailedOut.endsWith("|\n"))
      val stdOut = sumOut.split("\n")
      val stdOutHeader = stdOut(0).split("\\|")
      val stdOutValues = stdOut(1).split("\\|")
      // index of unsupportedExecs
      val stdOutunsupportedExecs = stdOutValues(stdOutValues.length - 3)
      // index of unsupportedExprs
      val stdOutunsupportedExprs = stdOutValues(stdOutValues.length - 2)
      val expectedstdOutExecs = "Scan;Filter;SerializeF..."
      assert(stdOutunsupportedExecs == expectedstdOutExecs)
      // Exec value is Scan;Filter;SerializeFromObject and UNSUPPORTED_EXECS_MAX_SIZE is 25
      val expectedStdOutExecsMaxLength = 25
      // Expr value is hex and length of expr header is 23 (Unsupported Expressions)
      val expectedStdOutExprsMaxLength = 23
      assert(stdOutunsupportedExecs.size == expectedStdOutExecsMaxLength)
      assert(stdOutunsupportedExprs.size == expectedStdOutExprsMaxLength)

      // run the qualification tool
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          eventLog))

        val (exit, sumInfo) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        //csv output tests
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults), "\"")
        val rows = outputActual.collect()
        assert(rows.size == 1)

        val expectedExecs = "Scan;Filter;SerializeFromObject" // Unsupported Execs
        val expectedExprs = "hex" //Unsupported Exprs
        val unsupportedExecs =
          outputActual.select(QualOutputWriter.UNSUPPORTED_EXECS).first.getString(0)
        val unsupportedExprs =
          outputActual.select(QualOutputWriter.UNSUPPORTED_EXPRS).first.getString(0)
        assert(expectedExecs == unsupportedExecs)
        assert(expectedExprs == unsupportedExprs)
      }
    }
  }

  test("running qualification app join") {
    TrampolineUtil.withTempDir { eventLogDir =>
      val qualApp = new RunningQualificationApp()
      val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "streaming") { spark =>
        val listener = qualApp.getEventListener
        spark.sparkContext.addSparkListener(listener)
        import spark.implicits._
        val testData = Seq((1, 2), (3, 4)).toDF("a", "b")
        testData.createOrReplaceTempView("t1")
        testData.createOrReplaceTempView("t2")
        spark.sql("SELECT a, MAX(b) FROM (SELECT t1.a, t2.b " +
          "FROM t1 JOIN t2 ON t1.a = t2.a) AS t " +
          "GROUP BY a ORDER BY a")
      }

      val sumOut = qualApp.getSummary()
      val detailedOut = qualApp.getDetailed()
      assert(sumOut.nonEmpty)
      assert(sumOut.startsWith("|") && sumOut.endsWith("|\n"))
      assert(detailedOut.nonEmpty)
      assert(detailedOut.startsWith("|") && detailedOut.endsWith("|\n"))

      // run the qualification tool
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          eventLog))

        val (exit, sumInfo) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults), "\"")
        val rowsDetailed = outputActual.collect()
        assert(rowsDetailed.size == 1)
        val headersDetailed = outputActual.columns
        val valuesDetailed = rowsDetailed(0)
        assert(headersDetailed.size == QualOutputWriter
          .getDetailedHeaderStringsAndSizes(Seq(qualApp.aggregateStats.get), false).keys.size)
        assert(headersDetailed.size == csvDetailedFields.size)
        assert(valuesDetailed.size == csvDetailedFields.size)
        // check all headers exists
        for (ind <- 0 until csvDetailedFields.size) {
          assert(csvDetailedHeader(ind).equals(headersDetailed(ind)))
        }

        // check that recommendation field is relevant to GPU Speed-up
        // Note that range-check does not apply for NOT-APPLICABLE
        val speedup =
          outputActual.select(QualOutputWriter.ESTIMATED_GPU_SPEEDUP).first.getDouble(0)
        val recommendation =
          outputActual.select(QualOutputWriter.SPEEDUP_BUCKET_STR).first.getString(0)
        assert(speedup >= 1.0)
        if (recommendation != QualificationAppInfo.NOT_APPLICABLE) {
          if (speedup >= QualificationAppInfo.LOWER_BOUND_STRONGLY_RECOMMENDED) {
            assert(recommendation == QualificationAppInfo.STRONGLY_RECOMMENDED)
          } else if (speedup >= QualificationAppInfo.LOWER_BOUND_RECOMMENDED) {
            assert(recommendation == QualificationAppInfo.RECOMMENDED)
          } else {
            assert(recommendation == QualificationAppInfo.NOT_RECOMMENDED)
          }
        }

        // check numeric fields skipping "Estimated Speed-up" on purpose
        val appDur = outputActual.select(QualOutputWriter.APP_DUR_STR).first.getLong(0)
        for (ind <- 4 until csvDetailedFields.size) {
          val (header, dt) = csvDetailedFields(ind)
          val fetched: Option[Double] = dt match {
            case DoubleType => Some(outputActual.select(header).first.getDouble(0))
            case LongType => Some(outputActual.select(header).first.getLong(0).doubleValue)
            case _ => None
          }
          if (fetched.isDefined) {
            val numValue = fetched.get
            if (header == "Unsupported Task Duration") {
              // unsupported task duration can be 0
              assert(numValue >= 0)
            } else if (header == "Executor CPU Time Percent") {
              // cpu percentage 0-100
              assert(numValue >= 0.0 && numValue <= 100.0)
            } else if (header == QualOutputWriter.GPU_OPPORTUNITY_STR ||
                        header == QualOutputWriter.SQL_DUR_STR) {
              // "SQL DF Duration" and "GPU Opportunity" cannot be larger than App Duration
              assert(numValue >= 0 && numValue <= appDur)
            } else {
              assert(numValue > 0)
            }
          }
        }
      }
    }
  }

  test("test csv output for unsupported operators") {
    TrampolineUtil.withTempDir { outpath =>
      val tmpJson = s"$outpath/jsonfile"
      TrampolineUtil.withTempDir { jsonOutputFile =>
        val (eventLog, _) = ToolTestUtils.generateEventLog(jsonOutputFile, "jsonFile") { spark =>
          import spark.implicits._
          val testData = Seq((1, 2), (3, 4)).toDF("a", "b")
          testData.write.json(tmpJson)
          val df = spark.read.json(tmpJson)
          val res = df.join(df.select($"a" as "a2"), $"a" === $"a2")
          res
        }
        val allArgs = Array(
          "--output-directory",
          outpath.getAbsolutePath())
        val appArgs = new QualificationArgs(allArgs ++ Array(eventLog))
        val (exit, appSum) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        val filename = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output_unsupportedOperators.csv"
        val inputSource = Source.fromFile(filename)
        try {
          val lines = inputSource.getLines.toSeq
          // 1 for header, 1 for values

          val expLinesSize =
            if (ToolUtils.isSpark340OrLater()) {
              8
            } else if (!ToolUtils.isSpark320OrLater()) {
              6
            } else {
              7
            }
          assert(lines.size == expLinesSize)
          assert(lines.head.contains("App ID,Unsupported Type,"))
          assert(lines(1).contains("\"Read\",\"JSON\",\"Types not supported - bigint:int\""))
        } finally {
          inputSource.close()
        }
      }
    }
  }

  test("running qualification app files with per sql") {
    TrampolineUtil.withTempPath { outParquetFile =>
      TrampolineUtil.withTempPath { outJsonFile =>

        val qualApp = new RunningQualificationApp()
        ToolTestUtils.runAndCollect("streaming") { spark =>
          val listener = qualApp.getEventListener
          spark.sparkContext.addSparkListener(listener)
          import spark.implicits._
          val testData = Seq((1, 2), (3, 4)).toDF("a", "b")
          testData.write.json(outJsonFile.getCanonicalPath)
          testData.write.parquet(outParquetFile.getCanonicalPath)
          val df = spark.read.parquet(outParquetFile.getCanonicalPath)
          val df2 = spark.read.json(outJsonFile.getCanonicalPath)
          df.join(df2.select($"a" as "a2"), $"a" === $"a2")
        }
        // just basic testing that line exists and has right separator
        val csvHeader = qualApp.getPerSqlCSVHeader
        assert(csvHeader.contains("App Name,App ID,SQL ID,SQL Description,SQL DF Duration," +
          "GPU Opportunity,Estimated GPU Duration,Estimated GPU Speedup," +
          "Estimated GPU Time Saved,Recommendation"))
        val txtHeader = qualApp.getPerSqlTextHeader
        assert(txtHeader.contains("|                              App Name|             App ID|" +
          "SQL ID" +
          "|                                                                                     " +
          "SQL Description|" +
          "SQL DF Duration|GPU Opportunity|Estimated GPU Duration|" +
          "Estimated GPU Speedup|Estimated GPU Time Saved|      Recommendation|"))
        val randHeader = qualApp.getPerSqlHeader(";", true, 20)
        assert(randHeader.contains(";                              App Name;             App ID" +
          ";SQL ID;     SQL Description;SQL DF Duration;GPU Opportunity;Estimated GPU Duration;" +
          "Estimated GPU Speedup;Estimated GPU Time Saved;      Recommendation;"))
        val allSQLIds = qualApp.getAvailableSqlIDs
        val numSQLIds = allSQLIds.size
        assert(numSQLIds > 0)
        val sqlIdToLookup = allSQLIds.head
        val (csvOut, txtOut) = qualApp.getPerSqlTextAndCSVSummary(sqlIdToLookup)
        assert(csvOut.contains("Profiling Tool Unit Tests") && csvOut.contains(","),
          s"CSV output was: $csvOut")
        assert(txtOut.contains("Profiling Tool Unit Tests") && txtOut.contains("|"),
          s"TXT output was: $txtOut")
        val sqlOut = qualApp.getPerSQLSummary(sqlIdToLookup, ":", true, 5)
        assert(sqlOut.contains("Tool Unit Tests:"), s"SQL output was: $sqlOut")

        // test different delimiter
        val sumOut = qualApp.getSummary(":", false)
        val rowsSumOut = sumOut.split("\n")
        assert(rowsSumOut.size == 2)
        val headers = rowsSumOut(0).split(":")
        val values = rowsSumOut(1).split(":")
        val appInfo = qualApp.aggregateStats()
        assert(appInfo.nonEmpty)
        assert(headers.size ==
          QualOutputWriter.getSummaryHeaderStringsAndSizes(30, 30).keys.size)
        assert(values.size == headers.size)
        // 3 should be the SQL DF Duration
        assert(headers(3).contains("SQL DF"))
        assert(values(3).toInt > 0)
        val detailedOut = qualApp.getDetailed(":", prettyPrint = false, reportReadSchema = true)
        val rowsDetailedOut = detailedOut.split("\n")
        assert(rowsDetailedOut.size == 2)
        val headersDetailed = rowsDetailedOut(0).split(":")
        val valuesDetailed = rowsDetailedOut(1).split(":")
        // Check Read Schema contains json and parquet
        val readSchemaIndex = headersDetailed.length - 1
        assert(headersDetailed(readSchemaIndex).contains("Read Schema"))
        assert(valuesDetailed(readSchemaIndex).contains("json") &&
            valuesDetailed(readSchemaIndex).contains("parquet"))
        qualApp.cleanupSQL(sqlIdToLookup)
        assert(qualApp.getAvailableSqlIDs.size == numSQLIds - 1)
      }
    }
  }

  test("test potential problems timestamp") {
    TrampolineUtil.withTempDir { eventLogDir =>
      val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "timezone") { spark =>
        import spark.implicits._
        val testData = Seq((1, 1662519019), (2, 1662519020)).toDF("id", "timestamp")
        spark.sparkContext.setJobDescription("timestamp functions as potential problems")
        testData.createOrReplaceTempView("t1")
        spark.sql("SELECT id, hour(current_timestamp()), second(to_timestamp(timestamp)) FROM t1")
      }

      // run the qualification tool
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
  
        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
        assert(outputActual.select("Potential Problems").first.getString(0) == 
          "TIMEZONE to_timestamp():TIMEZONE hour():TIMEZONE current_timestamp():TIMEZONE second()")
      }
    }
  }

  test("test existence join as supported join type") {
    TrampolineUtil.withTempDir { eventLogDir =>
      val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "existenceJoin") { spark =>
        import spark.implicits._
        val df1 = Seq(("A", 20, 90), ("B", 25, 91), ("C", 30, 94)).toDF("name", "age", "score")
        val df2 = Seq(("A", 15, 90), ("B", 25, 92), ("C", 30, 94)).toDF("name", "age", "score")
        df1.createOrReplaceTempView("tableA")
        df2.createOrReplaceTempView("tableB")
        spark.sql("SELECT * from tableA as l where l.age > 24 or exists" +
          " (SELECT  * from tableB as r where l.age=r.age and l.score <= r.score)")
      }
      // validate that the eventlog contains ExistenceJoin and BroadcastHashJoin
      val reader = Source.fromFile(eventLog).mkString
      assert(reader.contains("ExistenceJoin"))
      assert(reader.contains("BroadcastHashJoin"))

      // run the qualification tool
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          eventLog))

        val (exit, _) = QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        // validate that ExistenceJoin is supported since BroadcastHashJoin is not in unsupported
        // Execs list
        val outputActual = readExpectedFile(new File(outputResults), "\"")
        val unsupportedExecs = {
          outputActual.select(QualOutputWriter.UNSUPPORTED_EXECS).first.getString(0)
        }
        assert(!unsupportedExecs.contains("BroadcastHashJoin"))
      }
    }
  }

  test("test different values for platform argument") {
    TrampolineUtil.withTempDir { eventLogDir =>
      val (eventLog, _) = ToolTestUtils.generateEventLog(eventLogDir, "timezone") { spark =>
        import spark.implicits._
        val testData = Seq((1, 1662519019), (2, 1662519020)).toDF("id", "timestamp")
        spark.sparkContext.setJobDescription("timestamp functions as potential problems")
        testData.createOrReplaceTempView("t1")
        spark.sql("SELECT id, hour(current_timestamp()), second(to_timestamp(timestamp)) FROM t1")
      }

      // run the qualification tool for onprem
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "onprem",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
  
        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }

      // run the qualification tool for emr. It should default to emr-t4.
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "emr",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
  
        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }

      // run the qualification tool for emr-t4
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "emr-t4",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }

      // run the qualification tool for emr-a10
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "emr-a10",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }

      // run the qualification tool for dataproc. It should default to dataproc-t4
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "dataproc",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }

      // run the qualification tool for dataproc-t4
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "dataproc-t4",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)
  
        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }

      // run the qualification tool for dataproc-l4
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "dataproc-l4",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }

      // run the qualification tool for databricks-aws
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "databricks-aws",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }

      // run the qualification tool for databricks-azure
      TrampolineUtil.withTempDir { outpath =>
        val appArgs = new QualificationArgs(Array(
          "--output-directory",
          outpath.getAbsolutePath,
          "--platform",
          "databricks-azure",
          eventLog))

        val (exit, sumInfo) =
          QualificationMain.mainInternal(appArgs)
        assert(exit == 0)

        // the code above that runs the Spark query stops the Sparksession
        // so create a new one to read in the csv file
        createSparkSession()

        // validate that the SQL description in the csv file escapes commas properly
        val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
          s"rapids_4_spark_qualification_output.csv"
        val outputActual = readExpectedFile(new File(outputResults))
        assert(outputActual.collect().size == 1)
      }
    }
  }

  test("test frequency of repeated job") {
    val logFiles = Array(s"$logDir/empty_eventlog",  s"$logDir/nested_type_eventlog")
    runQualificationTest(logFiles, "multi_run_freq_test_expectation.csv")
  }

  test("test CSV qual output with escaped characters") {
    val jobNames = List("test,name",  "\"test\"name\"", "\"", ",", ",\"")
    jobNames.foreach { jobName =>
      TrampolineUtil.withTempDir { eventLogDir =>
        val (eventLog, _) =
          ToolTestUtils.generateEventLog(eventLogDir, jobName) { spark =>
            import spark.implicits._
            val testData = Seq((1), (2)).toDF("id")
            spark.sparkContext.setJobDescription("run job with problematic name")
            testData.createOrReplaceTempView("t1")
            spark.sql("SELECT id FROM t1")
          }

        // run the qualification tool
        TrampolineUtil.withTempDir { outpath =>
          val appArgs = new QualificationArgs(Array(
            "--per-sql",
            "--output-directory",
            outpath.getAbsolutePath,
            eventLog))

          val (exit, sumInfo) = QualificationMain.mainInternal(appArgs)
          assert(exit == 0)

          // the code above that runs the Spark query stops the Sparksession
          // so create a new one to read in the csv file
          createSparkSession()

          // validate that the SQL description in the csv file escapes commas properly
          val outputResults = s"$outpath/rapids_4_spark_qualification_output/" +
            s"rapids_4_spark_qualification_output.csv"
          val outputActual = readExpectedFile(new File(outputResults), "\"")
          assert(outputActual.select(QualOutputWriter.APP_NAME_STR).first.getString(0) == jobName)

          val persqlResults = s"$outpath/rapids_4_spark_qualification_output/" +
            s"rapids_4_spark_qualification_output_persql.csv"
          val outputPerSqlActual = readPerSqlFile(new File(persqlResults), "\"")
          val rows = outputPerSqlActual.collect()
          assert(rows(1)(0).toString == jobName)
        }
      }
    }
  }
}

class ToolTestListener extends SparkListener {
  val completedStages = new ListBuffer[SparkListenerStageCompleted]()
  var executorCpuTime = 0L

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {
    executorCpuTime += NANOSECONDS.toMillis(taskEnd.taskMetrics.executorCpuTime)
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = {
    completedStages.append(stageCompleted)
  }
}
