/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2005, 2025, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2011, Jens Elkner.
 * Portions Copyright (c) 2017, 2020, Chris Fraire <cfraire@me.com>.
 */
package org.opengrok.indexer.index;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.lang3.SystemUtils;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.annotations.VisibleForTesting;
import org.opengrok.indexer.Info;
import org.opengrok.indexer.Metrics;
import org.opengrok.indexer.analysis.AnalyzerGuru;
import org.opengrok.indexer.analysis.AnalyzerGuruHelp;
import org.opengrok.indexer.analysis.Ctags;
import org.opengrok.indexer.configuration.CanonicalRootValidator;
import org.opengrok.indexer.configuration.CommandTimeoutType;
import org.opengrok.indexer.configuration.Configuration;
import org.opengrok.indexer.configuration.ConfigurationHelp;
import org.opengrok.indexer.configuration.LuceneLockName;
import org.opengrok.indexer.configuration.Project;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.history.HistoryGuru;
import org.opengrok.indexer.history.RepositoriesHelp;
import org.opengrok.indexer.history.Repository;
import org.opengrok.indexer.history.RepositoryFactory;
import org.opengrok.indexer.history.RepositoryInfo;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.logger.LoggerUtil;
import org.opengrok.indexer.util.CtagsUtil;
import org.opengrok.indexer.util.Executor;
import org.opengrok.indexer.util.HostUtil;
import org.opengrok.indexer.util.OptionParser;
import org.opengrok.indexer.util.RuntimeUtil;
import org.opengrok.indexer.util.Statistics;

import static org.opengrok.indexer.util.RuntimeUtil.checkJavaVersion;

/**
 * Creates and updates an inverted source index as well as generates Xref, file
 * stats etc., if specified in the options.
 * <p>
 * We shall use / as path delimiter in whole opengrok for uuids and paths
 * from Windows systems, the path shall be converted when entering the index or web
 * and converted back if needed* to access original file
 * </p>
 * <p>
 * Windows already supports opening {@code /var/opengrok} as {@code C:\var\opengrok}
 * </p>
 */
@SuppressWarnings({"PMD.AvoidPrintStackTrace", "PMD.SystemPrintln", "java:S106"})
public final class Indexer {

    private static final Logger LOGGER = LoggerFactory.getLogger(Indexer.class);

    /* tunables for -r (history for remote repositories) */
    private static final String ON = "on";
    private static final String OFF = "off";
    private static final String DIRBASED = "dirbased";
    private static final String UIONLY = "uionly";

    //whole app uses this separator
    public static final char PATH_SEPARATOR = '/';
    public static final String PATH_SEPARATOR_STRING = Character.toString(PATH_SEPARATOR);

    private static final String HELP_OPT_1 = "--help";
    private static final String HELP_OPT_2 = "-?";
    private static final String HELP_OPT_3 = "-h";

    private static final Indexer indexer = new Indexer();
    private static Configuration cfg = null;
    private static boolean gotReadonlyConfiguration = false;
    private static IndexCheck.IndexCheckMode indexCheckMode = IndexCheck.IndexCheckMode.NO_CHECK;
    private static boolean runIndex = true;
    private static boolean reduceSegmentCount = false;
    private static boolean addProjects = false;
    private static boolean searchRepositories = false;
    private static boolean bareConfig = false;
    private static boolean awaitProfiler;
    private static boolean ignoreHistoryCacheFailures = false;

    private static boolean help;
    private static String helpUsage;
    private static HelpMode helpMode = HelpMode.DEFAULT;

    private static String configFilename = null;
    private static int status = 0;

    private static final Set<String> repositories = new HashSet<>();
    private static Set<String> searchPaths = new HashSet<>();
    private static final HashSet<String> allowedSymlinks = new HashSet<>();
    private static final HashSet<String> canonicalRoots = new HashSet<>();
    private static final Set<String> defaultProjects = new TreeSet<>();
    private static final HashSet<String> disabledRepositories = new HashSet<>();
    private static RuntimeEnvironment env = null;
    private static String webappURI = null;

    private static OptionParser optParser = null;
    private static boolean verbose = false;

    private static final String[] ON_OFF = {ON, OFF};
    private static final String[] REMOTE_REPO_CHOICES = {ON, OFF, DIRBASED, UIONLY};
    private static final String[] LUCENE_LOCKS = {ON, OFF, "simple", "native"};
    private static final String OPENGROK_JAR = "opengrok.jar";

    public static Indexer getInstance() {
        return indexer;
    }

    /**
     * Program entry point.
     *
     * @param argv argument vector
     */
    public static void main(String[] argv) {
        System.exit(runMain(argv));
    }

    /**
     * The body of {@link #main(String[])}. Avoids {@code System.exit()} so that it can be used for testing
     * without the test runner thinking the VM went away unexpectedly.
     * @param argv argument vector passed from the wrapper
     * @return 0 on success, positive number on error
     */
    @SuppressWarnings("PMD.UseStringBufferForStringAppends")
    @VisibleForTesting
    public static int runMain(String[] argv) {
        Statistics stats = new Statistics(); //this won't count JVM creation though

        Executor.registerErrorHandler();
        Set<String> subFilePaths = new HashSet<>(); // absolute paths
        Set<String> subFileArgs = new HashSet<>();  // relative to source root
        int exitCode = 0;

        try {
            argv = parseOptions(argv);

            if (webappURI != null && !HostUtil.isReachable(webappURI, cfg.getConnectTimeout(),
                    cfg.getIndexerAuthenticationToken())) {
                System.err.println(webappURI + " is not reachable and the -U option was specified, exiting.");
                return 1;
            }

            /*
             * Attend to disabledRepositories here in case exitWithHelp() will
             * need to report about repos.
             */
            disabledRepositories.addAll(cfg.getDisabledRepositories());
            cfg.setDisabledRepositories(disabledRepositories);
            for (String repoName : disabledRepositories) {
                LOGGER.log(Level.FINEST, "Disabled {0}", repoName);
            }

            if (help) {
                exitWithHelp();
            }

            checkConfiguration();

            if (awaitProfiler) {
                pauseToAwaitProfiler();
            }

            env = RuntimeEnvironment.getInstance();
            env.setIndexer(true);

            // Complete the configuration of repository types.
            List<Class<? extends Repository>> repositoryClasses = RepositoryFactory.getRepositoryClasses();
            for (Class<? extends Repository> clazz : repositoryClasses) {
                // Set external repository binaries from System properties.
                try {
                    Field f = clazz.getDeclaredField("CMD_PROPERTY_KEY");
                    Object key = f.get(null);
                    if (key != null) {
                        cfg.setRepoCmd(clazz.getCanonicalName(),
                                System.getProperty(key.toString()));
                    }
                } catch (Exception e) {
                    // don't care
                }
            }

            // Logging starts here.
            if (verbose) {
                String fn = LoggerUtil.getFileHandlerPattern();
                if (fn != null) {
                    System.out.println("Logging filehandler pattern: " + fn);
                }
            }

            // automatically allow symlinks that are directly in source root
            File sourceRootFile = new File(cfg.getSourceRoot());
            File[] projectDirs = sourceRootFile.listFiles();
            if (projectDirs != null) {
                for (File projectDir : projectDirs) {
                    if (!projectDir.getCanonicalPath().equals(projectDir.getAbsolutePath())) {
                        allowedSymlinks.add(projectDir.getAbsolutePath());
                    }
                }
            }

            allowedSymlinks.addAll(cfg.getAllowedSymlinks());
            cfg.setAllowedSymlinks(allowedSymlinks);

            canonicalRoots.addAll(cfg.getCanonicalRoots());
            cfg.setCanonicalRoots(canonicalRoots);

            // Assemble the unprocessed command line arguments (possibly a list of paths).
            // This will be used to perform more fine-grained checking in invalidateRepositories()
            // called from the setConfiguration() below.
            for (String arg : argv) {
                String path = Paths.get(cfg.getSourceRoot(), arg).toString();
                subFilePaths.add(path);
                subFileArgs.add(arg);
            }

            // If a user used customizations for projects he perhaps just
            // used the key value for project without a name but the code
            // expects a name for the project. Therefore, we fill the name
            // according to the project key which is the same.
            for (Entry<String, Project> entry : cfg.getProjects().entrySet()) {
                if (entry.getValue().getName() == null) {
                    entry.getValue().setName(entry.getKey());
                }
            }

            // Set updated configuration in RuntimeEnvironment. This is called so that the tunables set
            // via command line options are available.
            env.setConfiguration(cfg, subFilePaths, CommandTimeoutType.INDEXER);

            // Let repository types to add items to ignoredNames.
            // This changes env so is called after the setConfiguration() call above.
            RepositoryFactory.initializeIgnoredNames(env);

            // Check index(es) and exit. Use distinct return code upon failure.
            if (indexCheckMode.ordinal() > IndexCheck.IndexCheckMode.NO_CHECK.ordinal()) {
                checkIndexAndExit(subFileArgs);
            }

            if (bareConfig) {
                // Set updated configuration in RuntimeEnvironment.
                env.setConfiguration(cfg, subFilePaths, CommandTimeoutType.INDEXER);

                getInstance().sendToConfigHost(env, webappURI);
                writeConfigToFile(env, configFilename);
                return 0;
            }

            // The indexer does not support partial reindex, unless this is a case of per project reindex.
            if (!subFilePaths.isEmpty() && !env.isProjectsEnabled()) {
                System.err.println("Need to have projects enabled for the extra paths specified");
                return 1;
            }

            /*
             * Add paths to directories under source root. Each path must correspond to a project,
             * because project path is necessary to correctly set index directory
             * (otherwise the index files will end up in the 'index' directory directly underneath the data root
             * directory and not per project data root directory).
             * For the check we need to have the 'env' variable already set.
             */
            Set<Project> projects = new HashSet<>();
            for (String path : subFilePaths) {
                String srcPath = env.getSourceRootPath();
                if (srcPath == null) {
                    System.err.println("Error getting source root from environment. Exiting.");
                    return 1;
                }

                path = path.substring(srcPath.length());
                // The paths must correspond to a project.
                Project project;
                if ((project = Project.getProject(path)) != null) {
                    projects.add(project);
                    List<RepositoryInfo> repoList = env.getProjectRepositoriesMap().get(project);
                    if (repoList != null) {
                        repositories.addAll(repoList.
                                stream().map(RepositoryInfo::getDirectoryNameRelative).collect(Collectors.toSet()));
                    }
                } else {
                    System.err.println(String.format("The path '%s' does not correspond to a project", path));
                    return 1;
                }
            }

            if (!subFilePaths.isEmpty() && projects.isEmpty()) {
                System.err.println("None of the paths were added, exiting");
                return 1;
            }

            if (!projects.isEmpty() && configFilename != null) {
                LOGGER.log(Level.WARNING, "The collection of paths to process is non empty ({0}), seems like " +
                        "the intention is to perform per project reindex, however the -W option is used. " +
                        "This will likely not work.", projects);
            }

            Metrics.updateProjects(projects);

            // If the webapp is running with a config that does not contain
            // 'projectsEnabled' property (case of upgrade or transition
            // from project-less config to one with projects), set the property
            // so that the 'project/indexed' messages
            // emitted during indexing do not cause validation error.
            if (addProjects && webappURI != null) {
                enableProjectsInWebApp();
            }

            LOGGER.log(Level.INFO, "Indexer version {0} ({1}) running on Java {2} with properties: {3}",
                    new Object[]{Info.getVersion(), Info.getRevision(), RuntimeUtil.getJavaVersion(),
                            RuntimeUtil.getJavaProperties()});

            checkJavaVersion();

            // Create history cache first.
            if (searchRepositories) {
                if (searchPaths.isEmpty()) {
                    /*
                     * No search paths were specified. This means searching for the repositories under source root.
                     * To speed the process up, gather the directories directly underneath source root.
                     * The HistoryGuru#addRepositories(File[]) will search for the repositories
                     * in these directories in parallel.
                     */
                    String[] dirs = env.getSourceRootFile().
                            list((f, name) -> f.isDirectory() && env.getPathAccepter().accept(f));
                    if (dirs != null) {
                        searchPaths.addAll(Arrays.asList(dirs));
                    }
                }

                searchPaths = searchPaths.stream().
                        map(t -> Paths.get(env.getSourceRootPath(), t).toString()).
                        collect(Collectors.toSet());
            }
            Map<Repository, Optional<Exception>> historyCacheResults = getInstance().prepareIndexer(env,
                    searchPaths, addProjects, runIndex, new ArrayList<>(repositories));

            // Set updated configuration in RuntimeEnvironment. This is called so that repositories discovered
            // in prepareIndexer() are stored in the Configuration used by RuntimeEnvironment.
            env.setConfiguration(cfg, subFilePaths, CommandTimeoutType.INDEXER);

            // prepareIndexer() populated the list of projects so now default projects can be set.
            env.setDefaultProjectsFromNames(defaultProjects);

            // With the history cache results in hand, head over to the 2nd phase of the indexing.
            if (runIndex) {
                IndexChangedListener progress = new DefaultIndexChangedListener();
                if (ignoreHistoryCacheFailures) {
                    if (historyCacheResults.values().stream().anyMatch(Optional::isPresent)) {
                        LOGGER.log(Level.INFO, "There have been history cache creation failures, " +
                                        "however --ignoreHistoryCacheFailures was used, hence ignoring them: {0}",
                                historyCacheResults);
                    }
                    historyCacheResults = Collections.emptyMap();
                }
                getInstance().doIndexerExecution(projects, progress, historyCacheResults);
            }

            if (reduceSegmentCount) {
                IndexDatabase.reduceSegmentCountAll();
            }

            writeConfigToFile(env, configFilename);

            // Finally, send new configuration to the web application in the case of full reindex.
            if (webappURI != null && projects.isEmpty()) {
                getInstance().sendToConfigHost(env, webappURI);
            }
        } catch (ParseException e) {
            // This is likely a problem with processing command line arguments, hence print the error to standard
            // error output.
            System.err.println("** " + e.getMessage());
            exitCode = 1;
        } catch (IndexerException ex) {
            // The exception(s) were logged already however it does not hurt to reiterate them
            // at the very end of indexing (sans the stack trace) since they might have been buried in the log.
            LOGGER.log(Level.SEVERE, "Indexer failed with IndexerException");
            int i = 0;
            if (ex.getSuppressed().length > 0) {
                LOGGER.log(Level.INFO, "Suppressed exceptions ({0} in total):", ex.getSuppressed().length);
                for (Throwable throwable : ex.getSuppressed()) {
                    LOGGER.log(Level.INFO, "{0}: {1}", new Object[]{++i, throwable.getLocalizedMessage()});
                }
            }
            exitCode = 1;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, "Unexpected Exception", e);
            System.err.println("Exception: " + e.getLocalizedMessage());
            exitCode = 1;
        } finally {
            env.shutdownSearchExecutor();
            /*
             * Normally the IndexParallelizer is bounced (i.e. thread pools within are terminated)
             * via auto-closed in doIndexerExecution(), however there are cases (--noIndex) that
             * avoid that path, yet use the IndexParallelizer. So, bounce it here for a good measure.
             */
            env.getIndexerParallelizer().bounce();
            stats.report(LOGGER, "Indexer finished", "indexer.total");
        }

        if (exitCode == 0) {
            LOGGER.log(Level.INFO, "Indexer finished with success");
        }

        return exitCode;
    }

    private static void enableProjectsInWebApp() {
        try {
            IndexerUtil.enableProjects(webappURI);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, String.format("Couldn't notify the webapp on %s.", webappURI), e);
            System.err.printf("Couldn't notify the webapp on %s: %s.%n", webappURI, e.getLocalizedMessage());
        }
    }

    private static void checkIndexAndExit(Set<String> subFileArgs) {
        if (cfg.getDataRoot() == null || cfg.getDataRoot().isEmpty()) {
            System.err.println("Empty data root in configuration");
            System.exit(1);
        }

        try (IndexCheck indexCheck = new IndexCheck(cfg, subFileArgs)) {
            indexCheck.check(indexCheckMode);
        } catch (IOException e) {
            // Use separate return code for cases where the index could not be read.
            // This avoids problems with wiping out the index based on the check.
            LOGGER.log(Level.WARNING, String.format("Could not perform index check for '%s'", subFileArgs), e);
            System.exit(2);
        } catch (IndexCheckException e) {
            System.err.printf("Index check failed%n");
            if (!e.getFailedPaths().isEmpty()) {
                System.err.print("You might want to remove " + e.getFailedPaths());
            } else {
                System.err.println("with exception: " + e);
                e.printStackTrace(System.err);
            }
            System.exit(1);
        }

        System.exit(0);
    }

    /**
     * This is supposed to be run after {@link #parseOptions(String[])}.
     * It will exit the program if there is some serious configuration (meaning {@link #cfg}) discrepancy.
     */
    private static void checkConfiguration() {
        if (bareConfig && (env.getConfigURI() == null || env.getConfigURI().isEmpty())) {
            die("Missing webappURI setting");
        }

        if (!repositories.isEmpty() && !cfg.isHistoryEnabled()) {
            die("Repositories were specified; history is off however");
        }

        if (cfg.isAnnotationCacheEnabled() && !cfg.isHistoryEnabled()) {
            die("annotation cache is enabled however history is disabled. " +
                    "This cannot work as annotation cache stores latest revision retrieved from history.");
        }

        try {
            cfg.checkConfiguration();
        } catch (Configuration.ConfigurationException e) {
            die(e.getMessage());
        }
    }

    /**
     * Parse OpenGrok Indexer options
     * This method was created so that it would be easier to write unit
     * tests against the Indexer option parsing mechanism.
     * <p>
     * Limit usage lines to {@link org.opengrok.indexer.util.OptionParser.Option#MAX_DESCRIPTION_LINE_LENGTH}
     * characters for concise formatting.
     * </p>
     * @param argv the command line arguments
     * @return array of remaining non option arguments
     * @throws ParseException if parsing failed
     */
    public static String[] parseOptions(String[] argv) throws ParseException {
        final String[] usage = {HELP_OPT_1};

        if (argv.length == 0) {
            argv = usage;  // will force usage output
            status = 1; // with non-zero EXIT STATUS
        }

        /*
         * Pre-match any of the --help options so that some possible exception-generating args handlers (e.g. -R)
         * can be short-circuited.
         */
        boolean preHelp = Arrays.stream(argv).anyMatch(s -> HELP_OPT_1.equals(s) ||
                HELP_OPT_2.equals(s) || HELP_OPT_3.equals(s));

        OptionParser configure = OptionParser.scan(parser ->
                parser.on("-R configPath").execute(cfgFile -> {
            try {
                cfg = Configuration.read(new File((String) cfgFile));
                gotReadonlyConfiguration = true;
            } catch (IOException e) {
                if (!preHelp) {
                    die(e.getMessage());
                } else {
                    System.err.printf("Warning: failed to read -R %s%n", cfgFile);
                }
            }
        }));

        searchPaths.clear();

        optParser = OptionParser.execute(parser -> {
            parser.setPrologue(String.format("%nUsage: java -jar %s [options] [subDir1 [...]]%n", OPENGROK_JAR));

            parser.on(HELP_OPT_3, HELP_OPT_2, HELP_OPT_1, "=[mode]",
                    "With no mode specified, display this usage summary. Or specify a mode:",
                    "  config - display configuration.xml examples.",
                    "   ctags - display ctags command-line.",
                    "    guru - display AnalyzerGuru details.",
                    "   repos - display enabled repositories.").execute(v -> {
                        help = true;
                        helpUsage = parser.getUsage();
                        String mode = (String) v;
                        if (mode != null && !mode.isEmpty()) {
                            try {
                                helpMode = HelpMode.valueOf(((String) v).toUpperCase(Locale.ROOT));
                            } catch (IllegalArgumentException ex) {
                                die("mode '" + v + "' is not valid.");
                            }
                        }
            });

            parser.on("--annotationCache", "=on|off", ON_OFF, Boolean.class,
                            "Annotation cache provides speedup when getting annotation ",
                            "for files in the webapp at the cost of significantly increased ",
                            "indexing time (multiple times slower) and slightly increased ",
                            "disk space (comparable to history cache size). ",
                            "Can be enabled per project.").
                    execute(v -> cfg.setAnnotationCacheEnabled((Boolean) v));

            parser.on("--apiTimeout", "=number", Integer.class,
                    "Set timeout for asynchronous API requests.").execute(v -> cfg.setApiTimeout((Integer) v));

            parser.on("--connectTimeout", "=number", Integer.class,
                    "Set connect timeout. Used for API requests.").execute(v -> cfg.setConnectTimeout((Integer) v));

            parser.on(
                "-A (.ext|prefix.):(-|analyzer)", "--analyzer",
                    "/(\\.\\w+|\\w+\\.):(-|[a-zA-Z_0-9.]+)/",
                    "Associates files with the specified prefix or extension (case-",
                    "insensitive) to be analyzed with the given analyzer, where 'analyzer'",
                    "may be specified using a class name (case-sensitive e.g. RubyAnalyzer)",
                    "or analyzer language name (case-sensitive e.g. C). Option may be",
                    "repeated.",
                    "  Ex: -A .foo:CAnalyzer",
                    "      will use the C analyzer for all files ending with .FOO",
                    "  Ex: -A bar.:Perl",
                    "      will use the Perl analyzer for all files starting with",
                    "      \"BAR\" (no full-stop)",
                    "  Ex: -A .c:-",
                    "      will disable specialized analyzers for all files ending with .c").
                execute(analyzerSpec -> {
                    String[] arg = ((String) analyzerSpec).split(":");
                    String fileSpec = arg[0];
                    String analyzer = arg[1];
                    configureFileAnalyzer(fileSpec, analyzer);
                }
            );

            parser.on("-c", "--ctags", "=/path/to/ctags",
                    "Path to Universal Ctags. Default is ctags in environment PATH.").execute(
                            v -> cfg.setCtags((String) v));

            parser.on("--canonicalRoot", "=/path/",
                    "Allow symlinks to canonical targets starting with the specified root",
                    "without otherwise needing to specify -N,--symlink for such symlinks. A",
                    "canonical root must end with a file separator. For security, a canonical",
                    "root cannot be the root directory. Option may be repeated.").execute(v -> {
                String root = (String) v;
                String problem = CanonicalRootValidator.validate(root, "--canonicalRoot");
                if (problem != null) {
                    die(problem);
                }
                canonicalRoots.add(root);
            });

            parser.on("--checkIndex", "=[mode]",
                    "Check index, exit with 0 on success,",
                    "with 1 on legitimate failure, 2 on I/O error.",
                    "Has to be used with the -R option to read the configuration ",
                    "saved by previous indexer run via the -W option.",
                    "Selectable modes (exclusive):",
                    "  version - checks document version against indexer version",
                    "  documents - checks duplicate documents in the index",
                    "  definitions - check document definitions against file content (experimental)",
                    "With no mode specified, performs the version check."
                    ).execute(v -> {
                        if (!gotReadonlyConfiguration) {
                            die("option --checkIndex requires -R");
                        }

                        indexCheckMode = IndexCheck.IndexCheckMode.VERSION;
                        String mode = (String) v;
                        if (mode != null && !mode.isEmpty()) {
                            switch (mode) {
                                case "documents":
                                    indexCheckMode = IndexCheck.IndexCheckMode.DOCUMENTS;
                                    break;
                                case "definitions":
                                    indexCheckMode = IndexCheck.IndexCheckMode.DEFINITIONS;
                                    break;
                                case "version":
                                    // already set above
                                    break;
                                default:
                                    die("mode '" + mode + "' is not valid.");
                                    break;
                            }
                        }
                    }
            );

            parser.on("-d", "--dataRoot", "=/path/to/data/root",
                "The directory where OpenGrok stores the generated data.").
                execute(drPath -> {
                    File dataRoot = new File((String) drPath);
                    if (!dataRoot.exists() && !dataRoot.mkdirs()) {
                        die("Cannot create data root: " + dataRoot);
                    }
                    if (!dataRoot.isDirectory()) {
                        die("Data root must be a directory");
                    }
                    try {
                        cfg.setDataRoot(dataRoot.getCanonicalPath());
                    } catch (IOException e) {
                        die(e.getMessage());
                    }
                }
            );

            parser.on("--depth", "=number", Integer.class,
                "Scanning depth for repositories in directory structure relative to",
                "source root. Default is " + Configuration.DEFAULT_SCANNING_DEPTH + ".").execute(depth ->
                    cfg.setScanningDepth((Integer) depth));

            parser.on("--disableRepository", "=type_name",
                    "Disables operation of an OpenGrok-supported repository. See also",
                    "-h,--help repos. Option may be repeated.",
                    "  Ex: --disableRepository git",
                    "      will disable the GitRepository",
                    "  Ex: --disableRepository MercurialRepository").execute(v -> {
                String repoType = (String) v;
                String repoSimpleType = RepositoryFactory.matchRepositoryByName(repoType);
                if (repoSimpleType == null) {
                    System.err.printf("'--disableRepository %s' does not match a type and is ignored%n", v);
                } else {
                    disabledRepositories.add(repoSimpleType);
                }
            });

            parser.on("-e", "--economical",
                    "To consume less disk space, OpenGrok will not generate and save",
                    "hypertext cross-reference files but will generate on demand, which could",
                    "be slightly slow.").execute(v -> cfg.setGenerateHtml(false));

            parser.on("-G", "--assignTags",
                "Assign commit tags to all entries in history for all repositories.").execute(v ->
                    cfg.setTagsEnabled(true));

            // for backward compatibility
            parser.on("-H", "Enable history.").execute(v -> cfg.setHistoryEnabled(true));

            parser.on("--historyBased", "=on|off", ON_OFF, Boolean.class,
                            "If history based reindex is in effect, the set of files ",
                            "changed/deleted since the last reindex is determined from history ",
                            "of the repositories. This needs history, history cache and ",
                            "projects to be enabled. This should be much faster than the ",
                            "classic way of traversing the directory structure. ",
                            "The default is on. If you need to e.g. index files untracked by ",
                            "SCM, set this to off. Currently works only for Git and Mercurial.",
                            "All repositories in a project need to support this in order ",
                            "to be indexed using history.").
                    execute(v -> cfg.setHistoryBasedReindex((Boolean) v));

            parser.on("--historyThreads", "=number", Integer.class,
                    "The number of threads to use for history cache generation on repository level. ",
                    "By default the number of threads will be set to the number of available CPUs.",
                    "Assumes -H/--history.").execute(threadCount ->
                    cfg.setHistoryParallelism((Integer) threadCount));

            parser.on("--historyFileThreads", "=number", Integer.class,
                    "The number of threads to use for history cache generation ",
                    "when dealing with individual files.",
                    "By default the number of threads will be set to the number of available CPUs.",
                    "Assumes -H/--history.").execute(threadCount ->
                    cfg.setHistoryFileParallelism((Integer) threadCount));

            parser.on("-I", "--include", "=pattern",
                    "Only files matching this pattern will be examined. Pattern supports",
                    "wildcards (example: -I '*.java' -I '*.c'). Option may be repeated.").execute(
                            pattern -> cfg.getIncludedNames().add((String) pattern));

            parser.on("-i", "--ignore", "=pattern",
                    "Ignore matching files (prefixed with 'f:' or no prefix) or directories",
                    "(prefixed with 'd:'). Pattern supports wildcards (example: -i '*.so'",
                    "-i d:'test*'). Option may be repeated.").execute(pattern ->
                    cfg.getIgnoredNames().add((String) pattern));

            parser.on("--ignoreHistoryCacheFailures",
                    "Ignore history cache creation failures. By default if there is ",
                    "a history cache creation failure for a repository that corresponds ",
                    "to the source being indexed, the indexer will not proceed, ",
                    "because it will result either in indexing slow down or incomplete index.",
                    "This option overrides the failure. Assumes -H.").execute(v ->
                    ignoreHistoryCacheFailures = true);

            parser.on("-l", "--lock", "=on|off|simple|native", LUCENE_LOCKS,
                    "Set OpenGrok/Lucene locking mode of the Lucene database during index",
                    "generation. \"on\" is an alias for \"simple\". Default is off.").execute(v -> {
                try {
                    if (v != null) {
                        String vuc = v.toString().toUpperCase(Locale.ROOT);
                        cfg.setLuceneLocking(LuceneLockName.valueOf(vuc));
                    }
                } catch (IllegalArgumentException e) {
                    System.err.printf("`--lock %s' is invalid and ignored%n", v);
                }
            });

            parser.on("--leadingWildCards", "=on|off", ON_OFF, Boolean.class,
                "Allow or disallow leading wildcards in a search. Default is on.").execute(v ->
                    cfg.setAllowLeadingWildcard((Boolean) v));

            parser.on("-m", "--memory", "=number", Double.class,
                    "Amount of memory (MB) that may be used for buffering added documents and",
                    "deletions before they are flushed to the directory (default " +
                            Configuration.DEFAULT_RAM_BUFFER_SIZE + ").",
                    "Please increase JVM heap accordingly too.").execute(memSize ->
                    cfg.setRamBufferSize((Double) memSize));

            parser.on("--mandoc", "=/path/to/mandoc", "Path to mandoc(1) binary.")
                    .execute(mandocPath -> cfg.setMandoc((String) mandocPath));

            parser.on("-N", "--symlink", "=/path/to/symlink",
                    "Allow the symlink to be followed. Other symlinks targeting the same",
                    "canonical target or canonical children will be allowed too. Option may",
                    "be repeated. (By default only symlinks directly under the source root",
                    "directory are allowed. See also --canonicalRoot)").execute(v ->
                    allowedSymlinks.add((String) v));

            parser.on("-n", "--noIndex",
                    "Do not generate indexes and other data (such as history cache and xref",
                    "files), but process all other command line options.").execute(v ->
                    runIndex = false);

            parser.on("--nestingMaximum", "=number", Integer.class,
                    "Maximum depth of nested repositories. Default is 1.").execute(v ->
                    cfg.setNestingMaximum((Integer) v));

            parser.on("--reduceSegmentCount",
                    "Reduce the number of segments in each index database to 1. This might ",
                    "(or might not) bring some improved performance. Anyhow, this operation",
                    "takes non-trivial time to complete.").
                    execute(v -> reduceSegmentCount = true);

            parser.on("-o", "--ctagOpts", "=path",
                "File with extra command line options for ctags.").
                execute(path -> {
                    String CTagsExtraOptionsFile = (String) path;
                    File CTagsFile = new File(CTagsExtraOptionsFile);
                    if (!(CTagsFile.isFile() && CTagsFile.canRead())) {
                        die("File '" + CTagsExtraOptionsFile + "' not found for the -o option");
                    }
                    System.err.println("INFO: file with extra "
                        + "options for ctags: " + CTagsExtraOptionsFile);
                    cfg.setCTagsExtraOptionsFile(CTagsExtraOptionsFile);
                }
            );

            parser.on("-P", "--projects",
                "Generate a project for each top-level directory in source root.").execute(v -> {
                addProjects = true;
                cfg.setProjectsEnabled(true);
            });

            parser.on("-p", "--defaultProject", "=path/to/default/project",
                    "Path (relative to the source root) to a project that should be selected",
                    "by default in the web application (when no other project is set either",
                    "in a cookie or in parameter). Option may be repeated to specify several",
                    "projects. Use the special value __all__ to indicate all projects.").execute(v ->
                    defaultProjects.add((String) v));

            parser.on("--profiler", "Pause to await profiler or debugger.").
                execute(v -> awaitProfiler = true);

            parser.on("--progress",
                    "Print per-project percentage progress information.").execute(v ->
                    cfg.setPrintProgress(true));

            parser.on("-Q", "--quickScan",  "=on|off", ON_OFF, Boolean.class,
                    "Turn on/off quick context scan. By default, only the first 1024KB of a",
                    "file is scanned, and a link ('[..all..]') is inserted when the file is",
                    "bigger. Activating this may slow the server down. (Note: this setting",
                    "only affects the web application.) Default is on.").execute(v ->
                    cfg.setQuickContextScan((Boolean) v));

            parser.on("-q", "--quiet",
                    "Run as quietly as possible. Sets logging level to WARNING.").execute(v ->
                    LoggerUtil.setBaseConsoleLogLevel(Level.WARNING));

            parser.on("-R /path/to/configuration",
                "Read configuration from the specified file.").execute(v -> {
                // Already handled above. This populates usage.
            });

            parser.on("-r", "--remote", "=on|off|uionly|dirbased",
                REMOTE_REPO_CHOICES,
                "Specify support for remote SCM systems.",
                "      on - allow retrieval for remote SCM systems.",
                "     off - ignore SCM for remote systems.",
                "  uionly - support remote SCM for user interface only.",
                "dirbased - allow retrieval during history index only for repositories",
                "           which allow getting history for directories.").
                execute(v -> {
                    String option = (String) v;
                    if (option.equalsIgnoreCase(ON)) {
                        cfg.setRemoteScmSupported(Configuration.RemoteSCM.ON);
                    } else if (option.equalsIgnoreCase(OFF)) {
                        cfg.setRemoteScmSupported(Configuration.RemoteSCM.OFF);
                    } else if (option.equalsIgnoreCase(DIRBASED)) {
                        cfg.setRemoteScmSupported(Configuration.RemoteSCM.DIRBASED);
                    } else if (option.equalsIgnoreCase(UIONLY)) {
                        cfg.setRemoteScmSupported(Configuration.RemoteSCM.UIONLY);
                    }
                }
            );

            parser.on("--renamedHistory", "=on|off", ON_OFF, Boolean.class,
                "Enable or disable generating history for renamed files.",
                "If set to on, makes history indexing slower for repositories",
                "with lots of renamed files. Default is off.").execute(v ->
                    cfg.setHandleHistoryOfRenamedFiles((Boolean) v));

            parser.on("--repository", "=[path/to/repository|@file_with_paths]",
                    "Path (relative to the source root) to a repository for generating",
                    "history (if -H,--history is on). By default all discovered repositories",
                    "are history-eligible; using --repository limits to only those specified.",
                    "File containing paths can be specified via @path syntax.",
                    "Option may be repeated.")
                .execute(v -> handlePathParameter(repositories, ((String) v).trim()));

            parser.on("-S", "--search", "=[path/to/repository|@file_with_paths]",
                    "Search for source repositories under source root (-s,--source),",
                    "and add them. Path (relative to the source root) is optional. ",
                    "File containing the paths can be specified via @path syntax.",
                    "Option may be repeated.")
                .execute(v -> {
                        searchRepositories = true;
                        String value = ((String) v).trim();
                        if (!value.isEmpty()) {
                            handlePathParameter(searchPaths, value);
                        }
                    });

            parser.on("-s", "--source", "=/path/to/source/root",
                "The root directory of the source tree.").
                execute(source -> {
                    File sourceRoot = new File((String) source);
                    if (!sourceRoot.isDirectory()) {
                        die("Source root " + sourceRoot + " must be a directory");
                    }
                    try {
                        cfg.setSourceRoot(sourceRoot.getCanonicalPath());
                    } catch (IOException e) {
                        die(e.getMessage());
                    }
                }
            );

            parser.on("--style", "=path",
                    "Path to the subdirectory in the web application containing the requested",
                    "stylesheet. The factory-setting is: \"default\".").execute(stylePath ->
                    cfg.setWebappLAF((String) stylePath));

            parser.on("-T", "--threads", "=number", Integer.class,
                    "The number of threads to use for index generation, repository scan",
                    "and repository invalidation.",
                    "By default the number of threads will be set to the number of available",
                    "CPUs. This influences the number of spawned ctags processes as well.").
                    execute(threadCount -> cfg.setIndexingParallelism((Integer) threadCount));

            parser.on("-t", "--tabSize", "=number", Integer.class,
                "Default tab size to use (number of spaces per tab character).")
                    .execute(tabSize -> cfg.setTabSize((Integer) tabSize));

            parser.on("--token", "=string|@file_with_string",
                    "Authorization bearer API token to use when making API calls",
                    "to the web application").
                    execute(optarg -> {
                        String value = ((String) optarg).trim();
                        if (value.startsWith("@")) {
                            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                                    new FileInputStream(Path.of(value).toString().substring(1))))) {
                                String token = in.readLine().trim();
                                cfg.setIndexerAuthenticationToken(token);
                            } catch (IOException e) {
                                die("Failed to read from " + value);
                            }
                        } else {
                            cfg.setIndexerAuthenticationToken(value);
                        }
                    });

            parser.on("-U", "--uri", "=SCHEME://webappURI:port/contextPath",
                "Send the current configuration to the specified web application.").execute(webAddr -> {
                    webappURI = (String) webAddr;
                    try {
                        URI uri = new URI(webappURI);
                        String scheme = uri.getScheme();
                        if (!scheme.equals("http") && !scheme.equals("https")) {
                            die("webappURI '" + webappURI + "' does not have HTTP/HTTPS scheme");
                        }
                    } catch (URISyntaxException e) {
                        die("URL '" + webappURI + "' is not valid.");
                    }

                    env = RuntimeEnvironment.getInstance();
                    env.setConfigURI(webappURI);
                }
            );

            parser.on("---unitTest");  // For unit test only, will not appear in help

            parser.on("--updateConfig",
                    "Populate the web application with a bare configuration, and exit.").execute(v ->
                    bareConfig = true);

            parser.on("--userPage", "=URL",
                "Base URL of the user Information provider.",
                "Example: \"https://www.example.org/viewProfile.jspa?username=\".",
                "Use \"none\" to disable link.").execute(v -> cfg.setUserPage((String) v));

            parser.on("--userPageSuffix", "=URL-suffix",
                "URL Suffix for the user Information provider. Default: \"\".")
                    .execute(suffix -> cfg.setUserPageSuffix((String) suffix));

            parser.on("-V", "--version", "Print version, and quit.").execute(v -> {
                System.out.println(Info.getFullVersion());
                System.exit(0);
            });

            parser.on("-v", "--verbose", "Set logging level to INFO.").execute(v -> {
                verbose = true;
                LoggerUtil.setBaseConsoleLogLevel(Level.INFO);
            });

            parser.on("-W", "--writeConfig", "=/path/to/configuration",
                    "Write the current configuration to the specified file (so that the web",
                    "application can use the same configuration).").execute(configFile ->
                    configFilename = (String) configFile);

            parser.on("--webappCtags", "=on|off", ON_OFF, Boolean.class,
                    "Web application should run ctags when necessary. Default is off.").
                    execute(v -> cfg.setWebappCtags((Boolean) v));
        });

        // Need to read the configuration file first, so that options may be overwritten later.
        configure.parse(argv);

        LOGGER.log(Level.INFO, "Indexer options: {0}", Arrays.toString(argv));

        if (cfg == null) {
            cfg = new Configuration();
        }

        argv = optParser.parse(argv);

        return argv;
    }

    private static void die(String message) {
        System.err.println("ERROR: " + message);
        System.exit(1);
    }

    private static void configureFileAnalyzer(String fileSpec, String analyzer) {

        boolean prefix = false;

        // removing '.' from file specification
        // expecting either ".extensionName" or "prefixName."
        if (fileSpec.endsWith(".")) {
            fileSpec = fileSpec.substring(0, fileSpec.lastIndexOf('.'));
            prefix = true;
        } else {
            fileSpec = fileSpec.substring(1);
        }
        fileSpec = fileSpec.toUpperCase(Locale.ROOT);

        // Disable analyzer?
        if (analyzer.equals("-")) {
            if (prefix) {
                AnalyzerGuru.addPrefix(fileSpec, null);
            } else {
                AnalyzerGuru.addExtension(fileSpec, null);
            }
        } else {
            try {
                if (prefix) {
                    AnalyzerGuru.addPrefix(
                        fileSpec,
                        AnalyzerGuru.findFactory(analyzer));
                } else {
                    AnalyzerGuru.addExtension(
                        fileSpec,
                        AnalyzerGuru.findFactory(analyzer));
                }

            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException
                    | InvocationTargetException e) {
                LOGGER.log(Level.SEVERE, "Unable to locate FileAnalyzerFactory for {0}", analyzer);
                LOGGER.log(Level.SEVERE, "Stack: ", e.fillInStackTrace());
                System.exit(1);
            }
        }
    }

    /**
     * Write configuration to a file.
     * @param env runtime environment
     * @param filename file name to write the configuration to
     * @throws IOException if I/O exception occurred
     */
    public static void writeConfigToFile(RuntimeEnvironment env, String filename) throws IOException {
        if (filename != null) {
            LOGGER.log(Level.INFO, "Writing configuration to ''{0}''", filename);
            env.writeConfiguration(new File(filename));
            LOGGER.log(Level.INFO, "Done writing configuration to ''{0}''", filename);
        }
    }

    /**
     * Wrapper for prepareIndexer() that always generates history cache.
     * @return map of repository to optional exception
     */
    @TestOnly
    public Map<Repository, Optional<Exception>> prepareIndexer(RuntimeEnvironment env,
                                                               boolean searchRepositories,
                                                               boolean addProjects,
                                                               List<String> subFiles,
                                                               List<String> repositories) throws IndexerException, IOException {

        return prepareIndexer(env,
                searchRepositories ? Collections.singleton(env.getSourceRootPath()) : Collections.emptySet(),
                addProjects, true, repositories);
    }

    /**
     * Generate history cache and/or scan the repositories.
     * <p>
     * This is the first phase of the indexing where history cache is being
     * generated for repositories (at least for those which support getting
     * history per directory).
     * </p>
     *
     * @param env                runtime environment
     * @param searchPaths        list of paths relative to source root in which to search for repositories
     * @param addProjects        if true, add projects
     * @param createHistoryCache create history cache flag
     * @param repositories       list of repository paths relative to source root
     * @return map of repository to exception
     * @throws IndexerException indexer exception
     */
    public Map<Repository, Optional<Exception>> prepareIndexer(RuntimeEnvironment env,
                                                               Set<String> searchPaths,
                                                               boolean addProjects,
                                                               boolean createHistoryCache,
                                                               List<String> repositories) throws IndexerException {

        if (!env.validateUniversalCtags()) {
            throw new IndexerException("Could not find working Universal ctags. " +
                    "Pro tip: avoid installing Universal ctags from snap packages.");
        }

        // Projects need to be created first since when adding repositories below,
        // some project properties might be needed for that.
        if (addProjects) {
            File[] files = env.getSourceRootFile().listFiles();
            Map<String, Project> projects = env.getProjects();

            addProjects(files, projects);
        }

        if (!searchPaths.isEmpty()) {
            LOGGER.log(Level.INFO, "Scanning for repositories in {0} (down to {1} levels below source root)",
                    new Object[]{searchPaths, env.getScanningDepth()});
            Statistics stats = new Statistics();
            env.setRepositories(searchPaths.toArray(new String[0]));
            stats.report(LOGGER, String.format("Done scanning for repositories, found %d repositories",
                    env.getRepositories().size()), "indexer.repository.scan");
        }

        if (createHistoryCache) {
            // Even if history is disabled globally, it can be enabled for some repositories.
            Map<Repository, Optional<Exception>> historyCacheResults;
            if (repositories != null && !repositories.isEmpty()) {
                LOGGER.log(Level.INFO, "Generating history cache for repositories: {0}",
                        String.join(",", repositories));
                historyCacheResults = HistoryGuru.getInstance().createHistoryCache(repositories);
            } else {
                LOGGER.log(Level.INFO, "Generating history cache for all repositories ...");
                historyCacheResults = HistoryGuru.getInstance().createHistoryCache();
            }
            LOGGER.info("Done generating history cache");
            return historyCacheResults;
        }

        return Collections.emptyMap();
    }

    private void addProjects(File[] files, Map<String, Project> projects) {
        // Keep a copy of the old project list so that we can preserve
        // the customization of existing projects.
        Map<String, Project> oldProjects = new HashMap<>();
        for (Project p : projects.values()) {
            oldProjects.put(p.getName(), p);
        }

        projects.clear();

        // Add a project for each top-level directory in source root.
        for (File file : files) {
            String name = file.getName();
            String path = '/' + name;
            if (oldProjects.containsKey(name)) {
                // This is an existing object. Reuse the old project,
                // possibly with customizations, instead of creating a
                // new with default values.
                Project p = oldProjects.get(name);
                p.setPath(path);
                p.setName(name);
                p.completeWithDefaults();
                projects.put(name, p);
            } else if (!name.startsWith(".") && file.isDirectory()) {
                // Found a new directory with no matching project, so
                // create a new project with default properties.
                projects.put(name, new Project(name, path));
            }
        }
    }

    @VisibleForTesting
    public void doIndexerExecution(Set<Project> projects, IndexChangedListener progress) throws IOException, IndexerException {
        doIndexerExecution(projects, progress, Collections.emptyMap());
    }

    /**
     * This is the second phase of the indexer which generates Lucene index
     * by passing source code files through {@code ctags}, generating xrefs
     * and storing data from the source files in the index (along with history, if any).
     *
     * @param projects if not {@code null}, index just the projects specified
     * @param progress if not {@code null}, an object to receive notifications as indexer progress is made
     * @param historyCacheResults per repository results of history cache update
     * @throws IOException if I/O exception occurred
     * @throws IndexerException if the indexing has failed for any reason
     */
    public void doIndexerExecution(@Nullable Set<Project> projects, @Nullable IndexChangedListener progress,
                                   Map<Repository, Optional<Exception>> historyCacheResults)
            throws IOException, IndexerException {

        Statistics elapsed = new Statistics();
        LOGGER.info("Starting indexing");

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        try (IndexerParallelizer parallelizer = env.getIndexerParallelizer()) {
            final CountDownLatch latch;
            if (projects == null || projects.isEmpty()) {
                IndexDatabase.updateAll(progress, historyCacheResults);
            } else {
                // Setup with projects enabled is assumed here.
                List<IndexDatabase> dbs = new ArrayList<>();
                for (Project project : projects) {
                    addIndexDatabase(project, dbs, historyCacheResults);
                }

                final IndexerException indexerException = new IndexerException();
                latch = new CountDownLatch(dbs.size());
                for (final IndexDatabase db : dbs) {
                    db.addIndexChangedListener(progress);
                    parallelizer.getFixedExecutor().submit(() -> {
                        try {
                            db.update();
                        } catch (Throwable e) {
                            indexerException.addSuppressed(e);
                            LOGGER.log(Level.SEVERE, "An error occurred while updating index", e);
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                // Wait forever for the executors to finish.
                try {
                    LOGGER.info("Waiting for the executors to finish");
                    latch.await();
                } catch (InterruptedException exp) {
                    LOGGER.log(Level.WARNING, "Received interrupt while waiting for executor to finish", exp);
                    indexerException.addSuppressed(exp);
                }

                if (indexerException.getSuppressed().length > 0) {
                    throw indexerException;
                }
            }

            elapsed.report(LOGGER, "Done indexing data of all repositories", "indexer.repository.indexing");
        } finally {
            CtagsUtil.deleteTempFiles();
        }
    }

    private static void addIndexDatabase(Project project, List<IndexDatabase> dbs,
                                         Map<Repository, Optional<Exception>> historyCacheResults) throws IOException {
        IndexDatabase db;
        if (project == null) {
            db = new IndexDatabase();
            IndexDatabase.addIndexDatabase(db, dbs, historyCacheResults);
        } else {
            db = new IndexDatabase(project);
            IndexDatabase.addIndexDatabaseForProject(db, project, dbs, historyCacheResults);
        }
    }

    public void sendToConfigHost(RuntimeEnvironment env, String webAppURI) {
        LOGGER.log(Level.INFO, "Sending configuration to: {0}", webAppURI);
        try {
            env.writeConfiguration(webAppURI);
        } catch (IOException | IllegalArgumentException ex) {
            LOGGER.log(Level.SEVERE, String.format(
                    "Failed to send configuration to %s "
                    + "(is web application server running with OpenGrok deployed?)", webAppURI), ex);
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "interrupted while sending configuration to {0}", webAppURI);
        }
        LOGGER.info("Configuration update routine done, check log output for errors.");
    }

    private static void pauseToAwaitProfiler() {
        Scanner scan = new Scanner(System.in);
        String in;
        do {
            System.out.print("Start profiler. Continue (Y/N)? ");
            in = scan.nextLine().toLowerCase(Locale.ROOT);
        } while (!in.equals("y") && !in.equals("n"));

        if (in.equals("n")) {
            System.exit(1);
        }
    }

    // Visible for testing
    static void handlePathParameter(Collection<String> paramValueStore, String pathValue) {
        if (pathValue.startsWith("@")) {
            paramValueStore.addAll(loadPathsFromFile(pathValue.substring(1)));
        } else {
            paramValueStore.add(pathValue);
        }
    }

    private static List<String> loadPathsFromFile(String filename) {
        try {
            return Files.readAllLines(Path.of(filename));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, String.format("Could not load paths from %s", filename), e);
            throw new UncheckedIOException(e);
        }
    }

    private static void exitWithHelp() {
        PrintStream helpStream = status != 0 ? System.err : System.out;
        switch (helpMode) {
            case CONFIG:
                helpStream.print(ConfigurationHelp.getSamples());
                break;
            case CTAGS:
                /*
                 * Force the environment's ctags, because this method is called
                 * before main() does the heavyweight setConfiguration().
                 */
                env.setCtags(cfg.getCtags());
                helpStream.println("Ctags command-line:");
                helpStream.println();
                helpStream.println(getCtagsCommand());
                helpStream.println();
                break;
            case GURU:
                helpStream.println(AnalyzerGuruHelp.getUsage());
                break;
            case REPOS:
                /*
                 * Force the environment's disabledRepositories (as above).
                 */
                env.setDisabledRepositories(cfg.getDisabledRepositories());
                helpStream.println(RepositoriesHelp.getText());
                break;
            default:
                helpStream.println(helpUsage);
                break;
        }
        System.exit(status);
    }

    private static String getCtagsCommand() {
        Ctags ctags = new Ctags();
        return Executor.escapeForShell(ctags.getArgv(), true, SystemUtils.IS_OS_WINDOWS);
    }

    private enum HelpMode {
        CONFIG, CTAGS, DEFAULT, GURU, REPOS
    }

    private Indexer() {
    }
}
