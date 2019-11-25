/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.cli.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.build.ProjectionResult;
import software.amazon.smithy.build.SmithyBuild;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.cli.Arguments;
import software.amazon.smithy.cli.CliError;
import software.amazon.smithy.cli.Colors;
import software.amazon.smithy.cli.Command;
import software.amazon.smithy.cli.Parser;
import software.amazon.smithy.cli.SmithyCli;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;

public final class BuildCommand implements Command {
    private static final Logger LOGGER = Logger.getLogger(BuildCommand.class.getName());

    @Override
    public String getName() {
        return "build";
    }

    @Override
    public String getSummary() {
        return "Builds Smithy models and creates plugin artifacts for each projection";
    }

    @Override
    public Parser getParser() {
        return Parser.builder()
                .repeatedParameter("--config", "-c",
                     "Path to smithy-build.json configuration. Defaults to 'smithy-build.json'.")
                .parameter("--output", "-o", "Where to write artifacts. Defaults to 'build/smithy'.")
                .parameter("--projection", "Smithy will only generate artifacts for the given projection name.")
                .parameter("--plugin", "Smithy will only generate artifacts for the given plugin name.")
                .option(SmithyCli.DISCOVER, "-d", "Enables model discovery, merging in models found inside of jars")
                .parameter(SmithyCli.DISCOVER_CLASSPATH, "Enables model discovery using a custom classpath for models")
                .option(SmithyCli.ALLOW_UNKNOWN_TRAITS, "Ignores unknown traits when building models")
                .positional("<MODELS>", "Path to Smithy models or directories")
                .build();
    }

    @Override
    public void execute(Arguments arguments, ClassLoader classLoader) {
        List<String> config = arguments.repeatedParameter("--config", null);
        String output = arguments.parameter("--output", null);
        List<String> models = arguments.positionalArguments();

        LOGGER.info(String.format("Building Smithy model sources: %s", models));
        SmithyBuildConfig.Builder configBuilder = SmithyBuildConfig.builder();

        // Try to find a smithy-build.json file.
        if (config == null && Files.exists(Paths.get("smithy-build.json"))) {
            config = Collections.singletonList("smithy-build.json");
        }

        if (config != null) {
            LOGGER.info(String.format("Loading Smithy configs: [%s]", String.join(" ", config)));
            config.forEach(file -> configBuilder.load(Paths.get(file)));
        }

        if (output != null) {
            configBuilder.outputDirectory(output);
            try {
                Files.createDirectories(Paths.get(output));
                LOGGER.fine(String.format("Output directory set to: %s", output));
            } catch (IOException e) {
                throw new CliError("Unable to create Smithy output directory: " + e.getMessage());
            }
        }

        SmithyBuildConfig smithyBuildConfig = configBuilder.build();

        // Build the model and fail if there are errors.
        ValidatedResult<Model> sourceResult = buildModel(classLoader, models, arguments);
        Model model = sourceResult.unwrap();
        SmithyBuild smithyBuild = SmithyBuild.create(classLoader)
                .config(smithyBuildConfig)
                .model(model);

        if (arguments.has("--plugin")) {
            smithyBuild.pluginFilter(name -> name.equals(arguments.parameter("--plugin")));
        }

        if (arguments.has("--projection")) {
            smithyBuild.projectionFilter(name -> name.equals(arguments.parameter("--projection")));
        }

        // Register sources with the builder.
        models.forEach(path -> smithyBuild.registerSources(Paths.get(path)));

        ResultConsumer resultConsumer = new ResultConsumer();
        smithyBuild.build(resultConsumer, resultConsumer);

        // Always print out the status of the successful projections.
        Colors color = resultConsumer.failedProjections.isEmpty()
                ? Colors.BRIGHT_BOLD_GREEN
                : Colors.BRIGHT_BOLD_YELLOW;
        Colors.out(color, String.format(
                "Smithy built %s projection(s), %s plugin(s), and %s artifacts",
                resultConsumer.projectionCount,
                resultConsumer.pluginCount,
                resultConsumer.artifactCount));

        // Throw an exception if any errors occurred.
        if (!resultConsumer.failedProjections.isEmpty()) {
            resultConsumer.failedProjections.sort(String::compareTo);
            throw new CliError(String.format(
                    "The following %d Smithy build projection(s) failed: %s",
                    resultConsumer.failedProjections.size(),
                    resultConsumer.failedProjections));
        }
    }

    private ValidatedResult<Model> buildModel(ClassLoader classLoader, List<String> models, Arguments arguments) {
        ModelAssembler assembler = CommandUtils.createModelAssembler(classLoader);
        CommandUtils.handleModelDiscovery(arguments, assembler, classLoader);
        CommandUtils.handleUnknownTraitsOption(arguments, assembler);
        models.forEach(assembler::addImport);
        ValidatedResult<Model> result = assembler.assemble();
        Validator.validate(result, true);
        return result;
    }

    private static final class ResultConsumer implements Consumer<ProjectionResult>, BiConsumer<String, Throwable> {
        List<String> failedProjections = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger artifactCount = new AtomicInteger();
        AtomicInteger pluginCount = new AtomicInteger();
        AtomicInteger projectionCount = new AtomicInteger();

        @Override
        public void accept(String name, Throwable exception) {
            failedProjections.add(name);
            StringBuilder message = new StringBuilder(
                    String.format("%nProjection %s failed: %s%n", name, exception.toString()));

            for (StackTraceElement element : exception.getStackTrace()) {
                message.append(element).append(System.lineSeparator());
            }

            System.out.println(message);
        }

        @Override
        public void accept(ProjectionResult result) {
            if (result.isBroken()) {
                // Write out validation errors as they occur.
                failedProjections.add(result.getProjectionName());
                StringBuilder message = new StringBuilder(System.lineSeparator());
                message.append(result.getProjectionName())
                        .append(" has a model that failed validation")
                        .append(System.lineSeparator());
                result.getEvents().forEach(event -> {
                    if (event.getSeverity() == Severity.DANGER || event.getSeverity() == Severity.ERROR) {
                        message.append(event).append(System.lineSeparator());
                    }
                });
                Colors.out(Colors.RED, message.toString());
            } else {
                // Only increment the projection count if it succeeded.
                projectionCount.incrementAndGet();
            }

            pluginCount.addAndGet(result.getPluginManifests().size());

            // Get the base directory of the projection.
            Iterator<FileManifest> manifestIterator = result.getPluginManifests().values().iterator();
            Path root = manifestIterator.hasNext() ? manifestIterator.next().getBaseDir().getParent() : null;
            Colors.out(Colors.GREEN, String.format("Completed projection %s: %s", result.getProjectionName(), root));

            // Increment the total number of artifacts written.
            for (FileManifest manifest : result.getPluginManifests().values()) {
                artifactCount.addAndGet(manifest.getFiles().size());
            }
        }
    }
}