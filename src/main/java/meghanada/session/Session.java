package meghanada.session;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import meghanada.analyze.CompileResult;
import meghanada.analyze.Source;
import meghanada.completion.JavaCompletion;
import meghanada.completion.JavaVariableCompletion;
import meghanada.completion.LocalVariable;
import meghanada.config.Config;
import meghanada.location.Location;
import meghanada.location.LocationSearcher;
import meghanada.project.Project;
import meghanada.project.ProjectDependency;
import meghanada.project.gradle.GradleProject;
import meghanada.project.maven.MavenProject;
import meghanada.project.meghanada.MeghanadaProject;
import meghanada.reflect.CandidateUnit;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.utils.ClassNameUtils;
import meghanada.utils.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.EntryMessage;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Session {

    public static final String GRADLE_PROJECT_FILE = "build.gradle";
    private static final String PROJECT_CACHE = "project.dat";
    private static final String MVN_PROJECT_FILE = "pom.xml";
    private static final Logger log = LogManager.getLogger(Session.class);

    private static final Pattern SWITCH_TEST_RE = Pattern.compile("Test.java", Pattern.LITERAL);
    private static final Pattern SWITCH_JAVA_RE = Pattern.compile(".java", Pattern.LITERAL);
    private final LoadingCache<File, Source> sourceCache;
    private final SessionEventBus sessionEventBus;
    private Project currentProject;
    private JavaCompletion completion;
    private JavaVariableCompletion variableCompletion;
    private LocationSearcher locationSearcher;
    private Deque<Location> jumpDecHistory = new ArrayDeque<>(16);
    private boolean started;
    private HashMap<File, Project> projects = new HashMap<>();

    private Session(final Project currentProject) {
        this.currentProject = currentProject;
        this.sourceCache = CacheBuilder.newBuilder()
                .maximumSize(16)
                .expireAfterAccess(15, TimeUnit.MINUTES)
                .build(new JavaSourceLoader(currentProject));

        this.sessionEventBus = new SessionEventBus(this);
        this.started = false;
        this.locationSearcher = new LocationSearcher(currentProject.getAllSources(), this.sourceCache);
        this.projects.put(currentProject.getProjectRoot(), currentProject);
    }

    public static Session createSession(String root) throws IOException {
        return createSession(new File(root));
    }

    private static Session createSession(File root) throws IOException {
        root = root.getCanonicalFile();
        final Optional<Project> result = findProject(root);
        assert result != null;
        return result.map(Session::new)
                .orElseThrow(() -> new IllegalArgumentException("Project Not Found"));

    }

    public static Optional<Project> findProject(File base) throws IOException {
        while (true) {

            log.debug("finding project from '{}' ...", base);
            if (base.getPath().equals("/")) {
                return Optional.empty();
            }

            // challenge
            File gradle = new File(base, GRADLE_PROJECT_FILE);
            File mvn = new File(base, MVN_PROJECT_FILE);
            File meghanada = new File(base, Config.MEGHANADA_CONF_FILE);

            if (gradle.exists()) {
                log.debug("find gradle project {}", gradle);
                return loadProject(base, GRADLE_PROJECT_FILE);
            } else if (mvn.exists()) {
                log.debug("find mvn project {}", mvn);
                return loadProject(base, MVN_PROJECT_FILE);
            } else if (meghanada.exists()) {
                log.debug("find meghanada project {}", meghanada);
                return loadProject(base, Config.MEGHANADA_CONF_FILE);
            }

            File parent = base.getParentFile();
            if (parent == null) {
                return null;
            }
            base = base.getParentFile();
        }
    }

    private static Optional<Project> loadProject(final File projectRoot, final String targetFile) throws IOException {
        final EntryMessage entryMessage = log.traceEntry("projectRoot={} targetFile={}", projectRoot, targetFile);
        final String projectRootPath = projectRoot.getCanonicalPath();
        System.setProperty("project.root", projectRootPath);

        try {
            final Config config = Config.load();
            final String projectSettingDir = config.getProjectSettingDir();

            final File settingFile = new File(projectSettingDir);
            if (!settingFile.exists()) {
                settingFile.mkdirs();
            }
            final String id = FileUtils.findProjectID(projectRoot, targetFile);
            if (Project.loadedProject.containsKey(id)) {
                // loaded skip
                final Project project = Project.loadedProject.get(id);
                log.traceExit(entryMessage);
                System.setProperty("project.root", projectRootPath);
                return Optional.of(project);
            }

            log.trace("project projectID={} projectRoot={}", id, projectRoot);

            if (config.useFastBoot()) {

                final File projectCache = new File(projectSettingDir, PROJECT_CACHE);

                if (projectCache.exists()) {
                    try {
                        final Project tempProject = Session.readProjectCache(projectCache);
                        if (tempProject != null && tempProject.getId().equals(id)) {
                            tempProject.setId(id);
                            log.debug("load from cache project={}", tempProject);
                            log.info("load project from cache. projectRoot:{}", tempProject.getProjectRoot());
                            log.traceExit(entryMessage);
                            return Optional.of(tempProject.mergeFromProjectConfig());
                        }
                    } catch (Exception ex) {
                        // delete broken cache
                        projectCache.delete();
                    }
                }
            }

            Project project;
            if (targetFile.equals(GRADLE_PROJECT_FILE)) {
                project = new GradleProject(projectRoot);
            } else if (targetFile.equals(MVN_PROJECT_FILE)) {
                project = new MavenProject(projectRoot);
            } else {
                project = new MeghanadaProject(projectRoot);
            }
            project.setId(id);

            final Project parsed = project.parseProject();
            if (config.useFastBoot()) {
                final File projectCache = new File(projectSettingDir, PROJECT_CACHE);
                Session.writeProjectCache(parsed, projectCache);
            }
            log.info("load project projectRoot:{}", project.getProjectRoot());
            log.traceExit(entryMessage);
            return Optional.of(parsed.mergeFromProjectConfig());
        } finally {
            System.setProperty("project.root", projectRootPath);
        }
    }

    public static List<File> getSystemJars() throws IOException {
        final String javaHome = Config.load().getJavaHomeDir();
        File jvmDir = new File(javaHome);
        return Files.walk(jvmDir.toPath())
                .map(Path::toFile)
                .filter(f -> f.getName().endsWith(".jar") && !f.getName().endsWith("policy.jar"))
                .collect(Collectors.toList());
    }

    private static void writeProjectCache(final Project project, final File cacheFile) {
        final Kryo kryo = new Kryo();
        try (final Output out = new Output(new FileOutputStream(cacheFile))) {
            kryo.writeClassAndObject(out, project);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Project readProjectCache(final File cacheFile) throws IOException {
        final Kryo kryo = new Kryo();
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());

        try (final Input input = new Input(new FileInputStream(cacheFile))) {
            final Object o = kryo.readClassAndObject(input);
            log.debug("load project={}", o);
            if (o != null) {
                return (Project) o;
            }
            return null;
        }
    }

    public boolean clearCache() throws IOException {
        this.currentProject.clearCache();
        return true;
    }

    private boolean searchAndChangeProject(final File base) throws IOException {
        final File projectRoot = this.findProjectRoot(base);

        if (this.currentProject.getProjectRoot().equals(projectRoot)) {
            // not change
            return false;
        }
        if (this.projects.containsKey(projectRoot)) {
            // loaded project
            this.currentProject = this.projects.get(projectRoot);
            return false;
        }
        if (currentProject instanceof GradleProject) {
            return loadProject(projectRoot, GRADLE_PROJECT_FILE).map(project -> {
                this.currentProject = project;
                this.projects.put(projectRoot, this.currentProject);
                return true;
            }).orElse(false);
        } else if (currentProject instanceof MavenProject) {
            return loadProject(projectRoot, MVN_PROJECT_FILE).map(project -> {
                this.currentProject = project;
                this.projects.put(projectRoot, this.currentProject);
                return true;
            }).orElse(false);
        }
        return loadProject(projectRoot, Config.MEGHANADA_CONF_FILE).map(project -> {
            this.currentProject = project;
            this.projects.put(projectRoot, this.currentProject);
            return true;
        }).orElse(false);
    }

    private File findProjectRoot(File base) throws IOException {
        while (true) {

            log.debug("finding project from '{}' ...", base);
            if (base.getPath().equals("/")) {
                return null;
            }

            // challenge
            File gradle = new File(base, GRADLE_PROJECT_FILE);
            File mvn = new File(base, MVN_PROJECT_FILE);
            File meghanada = new File(base, Config.MEGHANADA_CONF_FILE);

            if (gradle.exists()) {
                log.debug("find gradle project {}", gradle);
                return base;
            } else if (mvn.exists()) {
                log.debug("find mvn project {}", mvn);
                return base;
            } else if (meghanada.exists()) {
                log.debug("find meghanada project {}", meghanada);
                return base;
            }

            File parent = base.getParentFile();
            if (parent == null) {
                return null;
            }
            base = base.getParentFile();
        }
    }

    private void setupSubscribes() throws IOException {
        // subscribe file watch
        this.sessionEventBus.subscribeFileWatch();
        this.sessionEventBus.subscribeParse();
        this.sessionEventBus.subscribeCache();
    }

    public Session start() throws IOException {
        if (this.started) {
            return this;
        }

        this.setupSubscribes();
        log.debug("session start");

        final Set<File> temp = new HashSet<>(this.getCurrentProject().getSourceDirectories());
        temp.addAll(this.getCurrentProject().getTestSourceDirectories());
        this.sessionEventBus.requestFileWatch(new ArrayList<>(temp));

        // load once
        final CachedASMReflector reflector = CachedASMReflector.getInstance();
        reflector.addClasspath(Session.getSystemJars());

        this.sessionEventBus.requestClassCache();

        log.debug("session started");
        this.started = true;
        return this;
    }

    public void shutdown(int timeout) {
        log.debug("session shutdown ...");

        this.sessionEventBus.shutdown(timeout);

        log.debug("session shutdown done");
    }

    public Project getCurrentProject() {
        return currentProject;
    }

    private JavaCompletion getCompletion() {
        if (this.completion == null) {
            this.completion = new JavaCompletion(this.sourceCache);
        }
        return this.completion;
    }

    public JavaVariableCompletion getVariableCompletion() {
        if (this.variableCompletion == null) {
            this.variableCompletion = new JavaVariableCompletion(this.sourceCache);
        }
        return variableCompletion;
    }

    public synchronized Collection<? extends CandidateUnit> completionAt(String path, int line, int column, String prefix) throws IOException, ClassNotFoundException, ExecutionException {
        // java file only
        File file = normalize(path);
        if (!FileUtils.isJavaFile(file)) {
            return Collections.emptyList();
        }
        return getCompletion().completionAt(file, line, column, prefix);
    }

    public synchronized boolean changeProject(final String path) {
        final File file = new File(path);

        if (this.started) {
            try {
                final boolean changed = this.searchAndChangeProject(file);
                if (changed) {
                    this.sessionEventBus.requestClassCache();
                } else {
                    // load source
                    this.sourceCache.get(file);
                }
                return true;
            } catch (Exception e) {
                log.catching(e);
                return false;
            }
        }

        return false;
    }

    public synchronized Optional<LocalVariable> localVariable(final String path, final int line) throws ExecutionException {
        // java file only
        final File file = normalize(path);
        if (!FileUtils.isJavaFile(file)) {
            return Optional.of(new LocalVariable("", Collections.emptyList()));
        }
        return getVariableCompletion().localVariable(file, line);
    }

    public synchronized boolean addImport(String path, String fqcn) throws ExecutionException {
        // java file only
        final File file = normalize(path);
        if (!FileUtils.isJavaFile(file)) {
            return false;
        }

        final Source source = parseJavaSource(file);
        source.importClass.put(ClassNameUtils.getSimpleName(fqcn), fqcn);
        return true;
    }

    public synchronized List<String> optimizeImport(String path) throws ExecutionException {
        // java file only
        File file = normalize(path);
        if (!FileUtils.isJavaFile(file)) {
            return Collections.emptyList();
        }

        final Source source = parseJavaSource(file);
        return source.optimizeImports();
    }

    public synchronized Map<String, List<String>> searchMissingImport(String path) throws ExecutionException {
        // java file only
        final File file = normalize(path);
        if (!FileUtils.isJavaFile(file)) {
            return Collections.emptyMap();
        }

        final Source source = parseJavaSource(file);
        return source.searchMissingImport();
    }

    public synchronized String getImplementTemplate(String fqcn) {
        return "";
    }

    private Source parseJavaSource(final File file) throws ExecutionException {
        return this.sourceCache.get(file);
    }

    public synchronized boolean parseFile(final String path) throws ExecutionException {
        // java file only
        final File file = normalize(path);
        if (!FileUtils.isJavaFile(file)) {
            return false;
        }
        this.sourceCache.invalidate(file);
        parseJavaSource(file);
        return true;
    }

    public synchronized CompileResult compileFile(final String path) throws IOException {
        // java file only
        final File file = normalize(path);
        final CompileResult result = currentProject.compileFileNoCache(file, true);
        this.invalidSouceCache(result);
        return result;
    }

    public synchronized CompileResult compileProject() throws IOException {
        final Project project = this.getCurrentProject();
        CompileResult result = project.compileJava(false);
        this.invalidSouceCache(result);
        if (result.isSuccess()) {
            result = project.compileTestJava(false);
            this.invalidSouceCache(result);
        }
        return result;
    }

    private void invalidSouceCache(final CompileResult compileResult) {
        compileResult.getSources().forEach((f, s) -> {
            this.sourceCache.invalidate(f);
        });
    }

    public Collection<File> getDependentJars() {
        return getCurrentProject()
                .getDependencies()
                .stream()
                .map(ProjectDependency::getFile)
                .collect(Collectors.toList());
    }

    public File getOutputDirectory() {
        return getCurrentProject()
                .getOutputDirectory();
    }

    public File getTestOutputDirectory() {
        return getCurrentProject()
                .getTestOutputDirectory();
    }

    private File normalize(String src) {
        File file = new File(src);
        if (!file.isAbsolute()) {
            file = new File(getCurrentProject().getProjectRoot(), src);
        }
        return file;
    }

    private File normalizeFile(File file) {
        if (!file.isAbsolute()) {
            file = new File(getCurrentProject().getProjectRoot(), file.getPath());
        }
        return file;
    }

    public InputStream runJUnit(String test) throws IOException {
        return getCurrentProject().runJUnit(test);
    }

    public String switchTest(String path) throws IOException {
        Project project = currentProject;
        String root = null;
        Set<File> roots;
        boolean isTest;

        if (path.endsWith("Test.java")) {
            // test -> src
            roots = project.getTestSourceDirectories();
            isTest = true;
        } else {
            // src -> test
            roots = project.getSourceDirectories();
            isTest = false;
        }

        for (File file : roots) {
            String rootPath = file.getCanonicalPath();
            if (path.startsWith(rootPath)) {
                root = rootPath;
                break;
            }
        }
        if (root == null) {
            return null;
        }

        String switchPath = path.substring(root.length());

        if (isTest) {
            switchPath = SWITCH_TEST_RE.matcher(switchPath).replaceAll(Matcher.quoteReplacement(".java"));
            // to src
            for (File srcRoot : project.getSourceDirectories()) {
                final File srcFile = new File(srcRoot, switchPath);
                if (srcFile.exists()) {
                    return srcFile.getCanonicalPath();
                }
            }

        } else {
            switchPath = SWITCH_JAVA_RE.matcher(switchPath).replaceAll(Matcher.quoteReplacement("Test.java"));
            // to test
            for (File srcRoot : project.getTestSourceDirectories()) {
                final File testFile = new File(srcRoot, switchPath);
                if (testFile.exists()) {
                    return testFile.getCanonicalPath();
                }
            }
        }

        return null;
    }

    String createJunitFile(String path) throws IOException, ExecutionException {
        Project project = this.getCurrentProject();
        String root = null;
        Set<File> roots = project.getSourceDirectories();

        for (File file : roots) {
            String rootPath = file.getCanonicalPath();
            if (path.startsWith(rootPath)) {
                root = rootPath;
                break;
            }
        }
        if (root == null) {
            return null;
        }

        String switchPath = path.substring(root.length());
        switchPath = switchPath.replace(".java", "Test.java");
        int srcSize = project.getTestSourceDirectories().size();
        // to test
        for (File srcRoot : project.getTestSourceDirectories()) {
            String srcRootPath = srcRoot.getCanonicalPath();
            if (srcSize > 1 && srcRootPath.contains(Project.DEFAULT_PATH)) {
                // skip default root
                continue;
            }
            File testFile = new File(srcRoot, switchPath);
            if (testFile.exists()) {
                return testFile.getCanonicalPath();
            } else {
                // create Junit
                this.createTestFile(path, testFile);
                return testFile.getCanonicalPath();
            }
        }
        return null;
    }

    private void createTestFile(String path, File testFile) throws IOException, ExecutionException {
        Source javaSource = this.parseJavaSource(new File(path));
        String pkg = javaSource.packageName;
        String testName = testFile.getName().replace(".java", "");

        String sb = "package " + pkg + ";\n"
                + "\n"
                + "import org.junit.After;\n"
                + "import org.junit.Before;\n"
                + "import org.junit.Test;\n"
                + "\n"
                + "import static org.junit.Assert.assertEquals;\n"
                + "\n"
                + "public class " + testName + " {\n"
                + "\n"
                + "    @Before\n"
                + "    public void setUp() throws Exception {\n"
                + "\n"
                + "    }\n"
                + "\n"
                + "    @After\n"
                + "    public void tearDown() throws Exception {\n"
                + "\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void test() throws Exception {\n"
                + "        assertEquals(1, 1);\n"
                + "    }\n"
                + "}\n";
        com.google.common.io.Files.write(sb, testFile, Charset.forName("UTF-8"));
    }

    public synchronized Location jumpDeclaration(final String path, final int line, final int column, final String symbol) throws ExecutionException, IOException {
        Location location = locationSearcher.searchDeclaration(new File(path), line, column, symbol);
        if (location != null) {
            Location backLocation = new Location(path, line, column);
            this.jumpDecHistory.addLast(backLocation);
        } else {
            log.warn("missing location path={} line={} column={} symbol={}", path, line, column, symbol);
            location = new Location(path, line, column);
        }
        return location;
    }

    public synchronized Location backDeclaration() {
        return this.jumpDecHistory.pollLast();
    }

    public LoadingCache<File, Source> getSourceCache() {
        return sourceCache;
    }

    public InputStream runTask(List<String> args) throws Exception {
        return getCurrentProject().runTask(args);
    }

    public String format(final String path) throws IOException {
        final Project project = getCurrentProject();
        FileUtils.formatJavaFile(project.getFormatProperties(), path);
        return path;
    }
}
