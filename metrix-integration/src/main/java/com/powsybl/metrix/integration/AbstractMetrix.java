/*
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.powsybl.metrix.integration;

import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.io.WorkingDirectory;
import com.powsybl.computation.ComputationManager;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.iidm.network.Network;
import com.powsybl.metrix.integration.dataGenerator.MetrixInputData;
import com.powsybl.metrix.integration.dataGenerator.MetrixOutputData;
import com.powsybl.metrix.integration.io.ResultListener;
import com.powsybl.metrix.integration.metrix.MetrixAnalysisResult;
import com.powsybl.metrix.mapping.*;
import com.powsybl.timeseries.ReadOnlyTimeSeriesStore;
import com.powsybl.timeseries.TimeSeriesIndex;
import com.powsybl.timeseries.ast.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public abstract class AbstractMetrix {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMetrix.class);

    protected static final String REMEDIAL_ACTIONS_CSV = "remedialActions.csv";
    protected static final String REMEDIAL_ACTIONS_CSV_GZ = REMEDIAL_ACTIONS_CSV + ".gz";

    protected static final String NETWORK_POINT_XIIDM = "network_point.xiidm";

    protected static final String LOG_FILE_PREFIX = "log";
    protected static final String LOG_FILE_DETAIL_PREFIX = "metrix";

    public static final String MAX_THREAT_PREFIX = MetrixOutputData.MAX_THREAT_NAME + "1_FLOW_";
    public static final String BASECASE_LOAD_PREFIX = "basecaseLoad_";
    public static final String BASECASE_OVERLOAD_PREFIX = "basecaseOverload_";
    public static final String OUTAGE_LOAD_PREFIX = "outageLoad_";
    public static final String OUTAGE_OVERLOAD_PREFIX = "outageOverload_";
    public static final String OVERALL_OVERLOAD_PREFIX = "overallOverload_";

    protected final ContingenciesProvider contingenciesProvider;

    protected final Reader remedialActionsReader;

    protected final ReadOnlyTimeSeriesStore store;

    protected final ReadOnlyTimeSeriesStore resultStore;

    protected final ZipOutputStream logArchive;

    protected final ComputationManager computationManager;

    protected final MetrixAppLogger appLogger;

    // Fields from analysis
    protected final TimeSeriesMappingConfig mappingConfig;

    @Nullable
    protected final MetrixDslData metrixDslData;

    private final Network network;

    private final MetrixParameters metrixParameters;

    protected AbstractMetrix(ContingenciesProvider contingenciesProvider, Reader remedialActionsReader,
                             ReadOnlyTimeSeriesStore store, ReadOnlyTimeSeriesStore resultStore,
                             ZipOutputStream logArchive, ComputationManager computationManager,
                             MetrixAppLogger appLogger, MetrixAnalysisResult analysisResult) {
        this.contingenciesProvider = contingenciesProvider;
        this.remedialActionsReader = remedialActionsReader;
        this.store = Objects.requireNonNull(store);
        this.resultStore = Objects.requireNonNull(resultStore);
        this.logArchive = logArchive;
        this.computationManager = Objects.requireNonNull(computationManager);
        this.appLogger = Objects.requireNonNull(appLogger);
        Objects.requireNonNull(analysisResult);
        this.mappingConfig = analysisResult.mappingConfig;
        this.metrixDslData = analysisResult.metrixDslData;
        this.network = analysisResult.network;
        this.metrixParameters = analysisResult.metrixParameters;
    }

    protected static void compress(Reader reader, WorkingDirectory directory, String fileNameGz) {
        try (BufferedReader bufferedReader = new BufferedReader(reader);
             Writer writer = new OutputStreamWriter(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(directory.toPath().resolve(fileNameGz)))), StandardCharsets.UTF_8)) {
            CharStreams.copy(bufferedReader, writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static int computeChunkSize(MetrixRunParameters runParameters, MetrixConfig metrixConfig, TimeSeriesIndex index) {
        if (runParameters.getChunkSize() != -1 && runParameters.getChunkSize() < index.getPointCount()) {
            return runParameters.getChunkSize();
        } else if (metrixConfig.getChunkSize() != -1 && metrixConfig.getChunkSize() < index.getPointCount()) {
            return metrixConfig.getChunkSize();
        } else {
            return index.getPointCount();
        }
    }

    public MetrixRunResult run(MetrixRunParameters runParameters, ResultListener listener) {
        Objects.requireNonNull(runParameters);
        Objects.requireNonNull(listener);

        MetrixConfig metrixConfig = MetrixConfig.load();

        if (metrixDslData != null) {
            int estimatedResultNumber = metrixDslData.minResultNumberEstimate(metrixParameters);
            if (estimatedResultNumber > metrixConfig.getResultNumberLimit()) {
                throw new PowsyblException(String.format("Metrix configuration will produce more result time-series (%d) than the maximum allowed (%d).\n" +
                        "Reduce the number of monitored branches and/or number of contingencies.", estimatedResultNumber, metrixConfig.getResultNumberLimit()));
            }
        }

        if (runParameters.isNetworkComputation()) {
            metrixParameters.setComputationType(MetrixComputationType.OPF);
            metrixParameters.setWithAdequacyResults(true);
            metrixParameters.setWithRedispatchingResults(true);
        }

        TimeSeriesIndex timeSeriesMappingIndex = mappingConfig.checkIndexUnicity(store);
        mappingConfig.checkValues(store, runParameters.getVersions());

        int firstVariant;
        if (runParameters.getFirstVariant() != -1) {
            if (runParameters.getFirstVariant() < 0 || runParameters.getFirstVariant() > timeSeriesMappingIndex.getPointCount() - 1) {
                throw new IllegalArgumentException("First variant is out of range [0, "
                        + (timeSeriesMappingIndex.getPointCount() - 1) + "]");
            }
            firstVariant = runParameters.getFirstVariant();
        } else {
            firstVariant = 0;
        }

        int lastVariant;
        if (runParameters.getVariantCount() != -1) {
            lastVariant = firstVariant + runParameters.getVariantCount() - 1;
            if (lastVariant > timeSeriesMappingIndex.getPointCount() - 1) {
                lastVariant = timeSeriesMappingIndex.getPointCount() - 1;
            }
        } else {
            lastVariant = timeSeriesMappingIndex.getPointCount() - 1;
        }

        int chunkSize = computeChunkSize(runParameters, metrixConfig, timeSeriesMappingIndex);

        ChunkCutter chunkCutter = new ChunkCutter(firstVariant, lastVariant, chunkSize);
        int chunkCount = chunkCutter.getChunkCount();

        LOGGER.info("Running metrix {} on network {}", metrixParameters.getComputationType(), network.getId());
        appLogger.log("Running metrix");

        try (WorkingDirectory commonWorkingDir = new WorkingDirectory(computationManager.getLocalDir(), "metrix-commons-", metrixConfig.isDebug())) {

            if (remedialActionsReader != null) {
                try (BufferedReader bufferedReader = new BufferedReader(remedialActionsReader);
                     Writer writer = new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(commonWorkingDir.toPath().resolve(REMEDIAL_ACTIONS_CSV))), StandardCharsets.UTF_8)) {
                    CharStreams.copy(bufferedReader, writer);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            executeMetrixChunks(
                    network,
                    runParameters,
                    listener,
                    metrixConfig,
                    metrixParameters,
                    commonWorkingDir,
                    chunkCutter,
                    chunkCount,
                    chunkSize);

            addLogsToArchive(runParameters, commonWorkingDir, chunkCount);
            listener.onEnd();

            MetrixRunResult runResult = new MetrixRunResult();
            appLogger.log("Computing postprocessing timeseries");
            runResult.setPostProcessingTimeSeries(getPostProcessingTimeSeries(metrixDslData, mappingConfig, resultStore));
            return runResult;

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void addLogsToArchive(
            MetrixRunParameters runParameters,
            WorkingDirectory commonWorkingDir,
            int chunkCount
    ) {
        if (logArchive == null) {
            return;
        }
        for (int version : runParameters.getVersions()) {
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                try {
                    addLogToArchive(commonWorkingDir.toPath().resolve(getLogFileName(version, chunk)), logArchive);
                    addLogDetailToArchive(commonWorkingDir.toPath(), getLogDetailFileNameFormat(version, chunk), logArchive);
                } catch (IOException e) {
                    LOGGER.error(e.toString(), e);
                    appLogger.tagged("info")
                            .log("Log file not found for chunk %d of version %d", chunk, version);
                }
            }
        }
    }

    protected abstract void executeMetrixChunks(
            Network network,
            MetrixRunParameters runParameters,
            ResultListener listener,
            MetrixConfig metrixConfig,
            MetrixParameters metrixParameters,
            WorkingDirectory commonWorkingDir,
            ChunkCutter chunkCutter,
            int chunkCount,
            int chunkSize
    ) throws IOException;

    protected static String getLogFileName(int version, int chunk) {
        return LOG_FILE_PREFIX + "_" + version + "_" + chunk + ".txt";
    }

    protected static String getLogDetailFileNameFormat(int version, int chunk) {
        return LOG_FILE_DETAIL_PREFIX + "%03d" + "_" + version + "_" + chunk + ".log";
    }

    protected static void addLogToArchive(Path logFile, ZipOutputStream logArchive) throws IOException {
        if (Files.exists(logFile)) {
            boolean gzipped = logFile.getFileName().toString().endsWith(".gz");
            String fileName = gzipped ? logFile.getFileName().toString().substring(0, logFile.getFileName().toString().length() - 3)
                    : logFile.getFileName().toString();
            ZipEntry zipEntry = new ZipEntry(fileName);
            logArchive.putNextEntry(zipEntry);
            try (InputStream is = gzipped ? new GZIPInputStream(Files.newInputStream(logFile))
                    : Files.newInputStream(logFile)) {
                ByteStreams.copy(is, logArchive);
            } finally {
                logArchive.closeEntry();
            }
        }
    }

    protected static void addLogDetailToArchive(Path workingDir, String logDetailFileNameFormat, ZipOutputStream logArchive) throws IOException {
        Path path;
        int i = 0;
        while (Files.exists(path = workingDir.resolve(String.format(logDetailFileNameFormat, i)))) {
            addLogToArchive(path, logArchive);
            i++;
        }
    }

    // Post-processing methods
    static Map<String, NodeCalc> getPostProcessingTimeSeries(MetrixDslData dslData,
                                                             TimeSeriesMappingConfig mappingConfig,
                                                             ReadOnlyTimeSeriesStore store) {
        Map<String, NodeCalc> postProcessingTimeSeries = new HashMap<>();
        if (dslData != null && mappingConfig != null) {
            Map<String, NodeCalc> calculatedTimeSeries = new HashMap<>(mappingConfig.getTimeSeriesNodes());
            createBasecasePostprocessingTimeSeries(dslData, mappingConfig, postProcessingTimeSeries, calculatedTimeSeries, store);
            createOutagePostprocessingTimeSeries(dslData, mappingConfig, postProcessingTimeSeries, calculatedTimeSeries, store);
        }
        return postProcessingTimeSeries;
    }

    private static NodeCalc createLoadTimeSeries(NodeCalc flowTimeSeries, NodeCalc ratingTimeSeries) {
        return BinaryOperation.multiply(BinaryOperation.div(flowTimeSeries, ratingTimeSeries), new FloatNodeCalc(100));
    }

    private static NodeCalc createLoadTimeSeries(NodeCalc flowTimeSeries, NodeCalc ratingTimeSeriesOrEx, NodeCalc ratingTimeSeriesExOr) {
        if (ratingTimeSeriesOrEx == ratingTimeSeriesExOr) {
            return createLoadTimeSeries(flowTimeSeries, ratingTimeSeriesOrEx);
        } else {
            NodeCalc zero = new IntegerNodeCalc(0);
            NodeCalc ratingTimeSeries = BinaryOperation.plus(BinaryOperation.multiply(BinaryOperation.greaterThan(flowTimeSeries, zero), ratingTimeSeriesOrEx),
                    BinaryOperation.multiply(BinaryOperation.lessThan(flowTimeSeries, zero), ratingTimeSeriesExOr));
            return createLoadTimeSeries(flowTimeSeries, ratingTimeSeries);
        }
    }

    private static NodeCalc createOverloadTimeSeries(NodeCalc flowTimeSeries, NodeCalc ratingTimeSeriesOrEx, NodeCalc ratingTimeSeriesExOr) {
        NodeCalc positiveOverloadTimeSeries = BinaryOperation.minus(flowTimeSeries, ratingTimeSeriesOrEx);
        NodeCalc negativeRatingTimeSeries = UnaryOperation.negative(ratingTimeSeriesExOr);
        NodeCalc negativeOverloadTimeSeries = BinaryOperation.minus(flowTimeSeries, negativeRatingTimeSeries);
        return BinaryOperation.plus(BinaryOperation.multiply(BinaryOperation.greaterThan(flowTimeSeries, ratingTimeSeriesOrEx), positiveOverloadTimeSeries),
                BinaryOperation.multiply(BinaryOperation.lessThan(flowTimeSeries, negativeRatingTimeSeries), negativeOverloadTimeSeries));
    }

    private static NodeCalc createOverallOverloadTimeSeries(NodeCalc basecaseOverloadTimeSeries, NodeCalc outageOverloadTimeSeries) {
        return BinaryOperation.plus(UnaryOperation.abs(basecaseOverloadTimeSeries), UnaryOperation.abs(outageOverloadTimeSeries));
    }

    private static void createBasecasePostprocessingTimeSeries(MetrixDslData metrixDslData,
                                                               TimeSeriesMappingConfig mappingConfig,
                                                               Map<String, NodeCalc> postProcessingTimeSeries,
                                                               Map<String, NodeCalc> calculatedTimeSeries,
                                                               ReadOnlyTimeSeriesStore store) {

        for (String branch : metrixDslData.getBranchMonitoringNList()) {
            MetrixInputData.MonitoringType branchMonitoringN = metrixDslData.getBranchMonitoringN(branch);
            if (branchMonitoringN == MetrixInputData.MonitoringType.MONITORING) {
                createBasecasePostprocessingTimeSeries(branch, MetrixVariable.thresholdN, MetrixVariable.thresholdNEndOr, mappingConfig, postProcessingTimeSeries, calculatedTimeSeries, store);
            } else if (branchMonitoringN == MetrixInputData.MonitoringType.RESULT && mappingConfig.getTimeSeriesName(new MappingKey(MetrixVariable.analysisThresholdN, branch)) != null) {
                createBasecasePostprocessingTimeSeries(branch, MetrixVariable.analysisThresholdN, MetrixVariable.analysisThresholdNEndOr, mappingConfig, postProcessingTimeSeries, calculatedTimeSeries, store);
            }
        }
    }

    private static void createBasecasePostprocessingTimeSeries(String branch,
                                                               MetrixVariable thresholdN,
                                                               MetrixVariable thresholdNEndOr,
                                                               TimeSeriesMappingConfig mappingConfig,
                                                               Map<String, NodeCalc> postProcessingTimeSeries,
                                                               Map<String, NodeCalc> calculatedTimeSeries,
                                                               ReadOnlyTimeSeriesStore store) {
        try {
            if (!store.timeSeriesExists(MetrixOutputData.FLOW_NAME + branch)) {
                LOGGER.debug("FLOW time-series not found for {}", branch);
                return;
            }
            LOGGER.debug("Creating basecase postprocessing time-series for {}", branch);
            NodeCalc flowTimeSeries = new TimeSeriesNameNodeCalc(MetrixOutputData.FLOW_NAME + branch);
            String ratingTimeSeriesName = mappingConfig.getTimeSeriesName(new MappingKey(thresholdN, branch));
            NodeCalc ratingTimeSeriesOrEx = calculatedTimeSeries.computeIfAbsent(ratingTimeSeriesName, TimeSeriesNameNodeCalc::new);

            NodeCalc ratingTimeSeriesExOr = ratingTimeSeriesOrEx;
            if (thresholdNEndOr != null) {
                ratingTimeSeriesName = mappingConfig.getTimeSeriesName(new MappingKey(thresholdNEndOr, branch));
                if (ratingTimeSeriesName != null) {
                    ratingTimeSeriesExOr = calculatedTimeSeries.computeIfAbsent(ratingTimeSeriesName, TimeSeriesNameNodeCalc::new);
                }
            }

            // Basecase load
            postProcessingTimeSeries.put(BASECASE_LOAD_PREFIX + branch, createLoadTimeSeries(flowTimeSeries, ratingTimeSeriesOrEx, ratingTimeSeriesExOr));
            // Basecase overload
            postProcessingTimeSeries.put(BASECASE_OVERLOAD_PREFIX + branch, createOverloadTimeSeries(flowTimeSeries, ratingTimeSeriesOrEx, ratingTimeSeriesExOr));
        } catch (IllegalStateException ise) {
            LOGGER.debug("Monitored branch {} not found in network", branch);
        }

    }

    private static void createOutagePostprocessingTimeSeries(MetrixDslData metrixDslData,
                                                             TimeSeriesMappingConfig mappingConfig,
                                                             Map<String, NodeCalc> postProcessingTimeSeries,
                                                             Map<String, NodeCalc> calculatedTimeSeries,
                                                             ReadOnlyTimeSeriesStore store) {

        for (String branch : metrixDslData.getBranchMonitoringNkList()) {
            MetrixInputData.MonitoringType branchMonitoringNk = metrixDslData.getBranchMonitoringNk(branch);
            if (branchMonitoringNk == MetrixInputData.MonitoringType.MONITORING) {
                createOutagePostprocessingTimeSeries(branch, MetrixVariable.thresholdN1, MetrixVariable.thresholdN1EndOr, mappingConfig, postProcessingTimeSeries, calculatedTimeSeries, store);
            } else if (branchMonitoringNk == MetrixInputData.MonitoringType.RESULT && mappingConfig.getTimeSeriesName(new MappingKey(MetrixVariable.analysisThresholdNk, branch)) != null) {
                createOutagePostprocessingTimeSeries(branch, MetrixVariable.analysisThresholdNk, MetrixVariable.analysisThresholdNkEndOr, mappingConfig, postProcessingTimeSeries, calculatedTimeSeries, store);
            }
        }
    }

    private static void createOutagePostprocessingTimeSeries(String branch,
                                                             MetrixVariable thresholdN1,
                                                             MetrixVariable thresholdN1EndOr,
                                                             TimeSeriesMappingConfig mappingConfig,
                                                             Map<String, NodeCalc> postProcessingTimeSeries,
                                                             Map<String, NodeCalc> calculatedTimeSeries,
                                                             ReadOnlyTimeSeriesStore store) {
        try {

            if (!store.timeSeriesExists(MAX_THREAT_PREFIX + branch)) {
                LOGGER.debug("MAX_THREAT time-series not found for {}", branch);
                return;
            }

            LOGGER.debug("Creating outage postprocessing time-series for {}", branch);

            NodeCalc flowTimeSeries = new TimeSeriesNameNodeCalc(MAX_THREAT_PREFIX + branch);
            String ratingTimeSeriesName = mappingConfig.getTimeSeriesName(new MappingKey(thresholdN1, branch));
            NodeCalc ratingTimeSeriesOrEx = calculatedTimeSeries.computeIfAbsent(ratingTimeSeriesName, TimeSeriesNameNodeCalc::new);

            NodeCalc ratingTimeSeriesExOr = ratingTimeSeriesOrEx;
            if (thresholdN1EndOr != null) {
                ratingTimeSeriesName = mappingConfig.getTimeSeriesName(new MappingKey(thresholdN1EndOr, branch));
                if (ratingTimeSeriesName != null) {
                    ratingTimeSeriesExOr = calculatedTimeSeries.computeIfAbsent(ratingTimeSeriesName, TimeSeriesNameNodeCalc::new);

                }
            }

            // Outage load
            postProcessingTimeSeries.put(OUTAGE_LOAD_PREFIX + branch, createLoadTimeSeries(flowTimeSeries, ratingTimeSeriesOrEx, ratingTimeSeriesExOr));
            // Outage overload
            NodeCalc outageOverLoadTimeSeries = createOverloadTimeSeries(flowTimeSeries, ratingTimeSeriesOrEx, ratingTimeSeriesExOr);
            postProcessingTimeSeries.put(OUTAGE_OVERLOAD_PREFIX + branch, outageOverLoadTimeSeries);
            NodeCalc basecaseOverLoadTimeSeries = postProcessingTimeSeries.get(BASECASE_OVERLOAD_PREFIX + branch);
            if (!Objects.isNull(basecaseOverLoadTimeSeries)) {
                postProcessingTimeSeries.put(OVERALL_OVERLOAD_PREFIX + branch, createOverallOverloadTimeSeries(basecaseOverLoadTimeSeries, outageOverLoadTimeSeries));
            }
        } catch (IllegalStateException ise) {
            LOGGER.debug("Monitored branch {} not found in network", branch);
        }
    }
}
