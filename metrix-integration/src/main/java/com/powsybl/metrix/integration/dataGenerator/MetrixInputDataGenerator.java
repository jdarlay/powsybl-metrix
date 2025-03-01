package com.powsybl.metrix.integration.dataGenerator;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import com.powsybl.computation.*;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.iidm.network.Network;
import com.powsybl.metrix.integration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class MetrixInputDataGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetrixInputDataGenerator.class);

    private static final List<InputFile> FORT_FILE_NAME = Collections.singletonList(new InputFile("fort.json"));
    private static final String VARIANTES_FILE_NAME = "variantes.csv";
    private static final String LOGS_FILE_NAME = "logs.txt";
    private static final String METRIX_COMMAND_ID = "metrix";
    private static final String METRIX_PROGRAM = "metrix-simulator";
    private static final String METRIX_LOG_LEVEL_ARG = "--log-level=";

    public static final String REMEDIAL_ACTION_FILE_NAME = "parades.csv";

    private final MetrixConfig config;
    private final Path workingDir;
    private final MetrixChunkLogger metrixChunkLogger;
    public final FileSystemUtils files;

    public MetrixInputDataGenerator(MetrixConfig config, Path workingDir, MetrixChunkLogger metrixChunkLogger) {
        this(config, workingDir, metrixChunkLogger, FileSystemUtils.defaultFiles());
    }

    protected MetrixInputDataGenerator(MetrixConfig config, Path workingDir, MetrixChunkLogger metrixChunkLogger, FileSystemUtils files) {
        this.config = config;
        this.workingDir = workingDir;
        this.metrixChunkLogger = MetrixChunkLogger.neverNull(metrixChunkLogger);
        this.files = files;
    }

    public void generateInputFileZip(Path remedialActionFile,
                                     MetrixVariantProvider variantProvider, Network network,
                                     ContingenciesProvider contingenciesProvider, MetrixParameters parameters,
                                     MetrixDslData metrixDslData) throws IOException {
        inputFiles(
                remedialActionFile,
                variantProvider,
                network,
                contingenciesProvider,
                parameters,
                metrixDslData,
                CopyInputAdditionalFiles.nothing(),
                defineVariantValue(variantProvider));
    }

    public List<CommandExecution> generateMetrixInputData(Path remedialActionFile,
                                                          MetrixVariantProvider variantProvider, Network network,
                                                          ContingenciesProvider contingenciesProvider,
                                                          MetrixParameters parameters,
                                                          MetrixDslData metrixDslData) throws IOException {
        MetrixVariantProvider.Variants variants = defineVariantValue(variantProvider);
        List<InputFile> inputFiles = inputFiles(remedialActionFile, variantProvider, network, contingenciesProvider, parameters, metrixDslData, this::copyToInputFiles, variants);
        List<OutputFile> outputFiles = outputFiles(variants);
        return commandExecutionFrom(command(config, variants, inputFiles, outputFiles));
    }

    private List<CommandExecution> commandExecutionFrom(Command command) {
        // overload HADES_DIR variable with working dir
        ImmutableMap<String, String> overloadedVariables = ImmutableMap.of("HADES_DIR", ".");
        return Collections.singletonList(new CommandExecution(command, 1, 0, null, overloadedVariables));
    }

    private Command command(MetrixConfig config, MetrixVariantProvider.Variants variants, List<InputFile> inputFiles, List<OutputFile> outputFiles) {
        return new SimpleCommandBuilder()
                .id(METRIX_COMMAND_ID)
                .program(METRIX_PROGRAM)
                .args(LOGS_FILE_NAME,
                        VARIANTES_FILE_NAME,
                        MetrixOutputData.FILE_NAME_PREFIX,
                        Integer.toString(variants.firstVariant()),
                        Integer.toString(variants.variantCount()),
                        logArg(config.logLevel())
                )
                .inputFiles(inputFiles)
                .outputFiles(outputFiles)
                .build();
    }

    interface CopyInputAdditionalFiles {
        static CopyInputAdditionalFiles nothing() {
            return (_a, _b) -> {
            };
        }

        void copyToInputFiles(Path remedialActionFile, List<InputFile> inputFiles);
    }

    private List<InputFile> inputFiles(Path remedialActionFile, MetrixVariantProvider variantProvider, Network network, ContingenciesProvider contingenciesProvider, MetrixParameters parameters, MetrixDslData metrixDslData, CopyInputAdditionalFiles additionnal, MetrixVariantProvider.Variants variants) throws IOException {
        LOGGER.info("Generating Metrix chunk input data in '{}'", workingDir.toAbsolutePath());
        List<InputFile> inputFiles = new ArrayList<>(FORT_FILE_NAME);
        additionnal.copyToInputFiles(remedialActionFile, inputFiles);
        MetrixNetwork metrixNetwork = createNetwork(remedialActionFile, variantProvider, network, contingenciesProvider, parameters);
        writeFileData(variantProvider, parameters, metrixDslData, variants, metrixNetwork);
        return inputFiles;
    }

    protected MetrixNetwork createNetwork(Path remedialActionFile, MetrixVariantProvider variantProvider, Network network, ContingenciesProvider contingenciesProvider, MetrixParameters parameters) {
        return MetrixNetwork.create(network, contingenciesProvider,
                variantProvider != null ? variantProvider.getMappedBreakers() : null, parameters, remedialActionFile);
    }

    private void copyToInputFiles(Path remedialActionFile, List<InputFile> inputFiles) {
        copyDic(inputFiles); // copy METRIX*.dic
        copyInputFile(remedialActionFile, REMEDIAL_ACTION_FILE_NAME, inputFiles); // copy parades.csv
    }

    private List<OutputFile> outputFiles(MetrixVariantProvider.Variants variants) {
        List<OutputFile> outputFiles = new ArrayList<>(1 + variants.count());
        for (int variantNum = variants.firstVariant(); variantNum <= variants.lastVariant(); variantNum++) {
            outputFiles.add(new OutputFile(MetrixOutputData.getFileName(variantNum)));
        }
        outputFiles.add(new OutputFile(LOGS_FILE_NAME));
        return outputFiles;
    }

    private MetrixVariantProvider.Variants defineVariantValue(MetrixVariantProvider variantProvider) {
        MetrixVariantProvider.Variants variants = variantProvider == null
                ? MetrixVariantProvider.Variants.empty()
                : variantProvider.variants();
        return variants;
    }

    private void writeFileData(MetrixVariantProvider variantProvider,
                               MetrixParameters parameters, MetrixDslData metrixDslData,
                               MetrixVariantProvider.Variants variants, MetrixNetwork metrixNetwork) throws IOException {
        writeVariants(variantProvider, variants, metrixNetwork);
        writeNetwork(parameters, metrixDslData, metrixNetwork);
    }

    private void writeNetwork(MetrixParameters parameters, MetrixDslData metrixDslData, MetrixNetwork metrixNetwork) throws IOException {
        Supplier<MetrixInputData> metrixInputData = createMetrixInputData(parameters, metrixDslData, metrixNetwork);
        writeNetworkInLogger(metrixInputData, config.isConstantLossFactor());
    }

    protected void writeNetworkInLogger(Supplier<MetrixInputData> metrixInputData, boolean angleDePerteFixe) throws IOException {
        metrixChunkLogger.writeNetwork(() -> {
            // write DIE
            metrixInputData.get().write(workingDir, true, angleDePerteFixe);
        });
    }

    protected Supplier<MetrixInputData> createMetrixInputData(MetrixParameters parameters, MetrixDslData metrixDslData, MetrixNetwork metrixNetwork) {
        return () -> new MetrixInputData(metrixNetwork, metrixDslData, parameters);
    }

    private void writeVariants(MetrixVariantProvider variantProvider, MetrixVariantProvider.Variants variants, MetrixNetwork metrixNetwork) throws IOException {
        MetrixVariantsWriter writer = variantProvider != null
                ? createMetrixVariantsWriter(variantProvider, metrixNetwork)
                : createMetrixVariantsWriter(null, null);
        Range<Integer> variantRange = variantProvider == null ? null : variants.closedRange();
        writeVariantsInLogger(variants, writer, variantRange);
    }

    protected void writeVariantsInLogger(MetrixVariantProvider.Variants variants, MetrixVariantsWriter writer, Range<Integer> variantRange) throws IOException {
        metrixChunkLogger.writeVariants(variants.count(), () -> writer.write(
                variantRange, workingDir.resolve(VARIANTES_FILE_NAME), workingDir)
        );
    }

    private MetrixVariantsWriter createMetrixVariantsWriter(MetrixVariantProvider variantProvider, MetrixNetwork metrixNetwork) {
        return new MetrixVariantsWriter(variantProvider, metrixNetwork);
    }

    public interface FileSystemUtils {
        final FileSystemUtils DEFAULT = new FileSystemUtils() {
            @Override
            public boolean isRegularFile(Path p) {
                return Files.isRegularFile(p);
            }

            @Override
            public Stream<Path> list(Path p) throws IOException {
                return Files.list(p);
            }

            @Override
            public Path copy(Path src, Path dest) throws IOException {
                return Files.copy(src, dest);
            }
        };

        static FileSystemUtils defaultFiles() {
            return DEFAULT;
        }

        boolean isRegularFile(Path p);

        Stream<Path> list(Path p) throws IOException;

        Path copy(Path src, Path dest) throws IOException;
    }

    public void copyDic(List<InputFile> inputFiles) {
        try (Stream<Path> stream = files.list(config.getHomeDir().resolve("etc"))
                .filter(files::isRegularFile)
                .filter(path -> path.getFileName().toString().matches("METRIX(.*)\\.dic"))) {
            stream.forEach(dicFile -> copyInputFile(dicFile, dicFile.getFileName().toString(), inputFiles));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void copyInputFile(Path originPath, String destinationFileName, List<InputFile> inputFiles) {
        if (originPath != null) {
            try {
                files.copy(originPath, workingDir.resolve(destinationFileName));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            inputFiles.add(new InputFile(destinationFileName));
        }
    }

    private String logArg(String logLevel) {
        if (logLevel.isEmpty()) {
            LOGGER.warn("Unknown Metrix log level value '{}'", logLevel);
            return "";
        }
        return METRIX_LOG_LEVEL_ARG + logLevel;
    }
}
