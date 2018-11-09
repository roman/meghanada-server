package meghanada.junit;

import static java.util.Objects.nonNull;

import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import meghanada.reflect.ClassIndex;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.store.ProjectDatabaseHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.launcher.listeners.TestExecutionSummary.Failure;

public class TestRunner {

  public static final String TEMP_PROJECT_SETTING_DIR = "meghanada.temp.project.setting.dir";
  private static Logger log = LogManager.getLogger(TestRunner.class);
  private long runCnt;
  private long failureCnt;
  private long ignoreCnt;

  public TestRunner() throws IOException {

    final File tempDir = Files.createTempDir();
    tempDir.deleteOnExit();
    final String path = tempDir.getCanonicalPath();
    System.setProperty(TEMP_PROJECT_SETTING_DIR, path);

    final String output = System.getProperty("meghanada.output");
    final String testOutput = System.getProperty("meghanada.test-output");
    CachedASMReflector cachedASMReflector = CachedASMReflector.getInstance();
    cachedASMReflector.addClasspath(new File(output));
    cachedASMReflector.addClasspath(new File(testOutput));
    cachedASMReflector.createClassIndexes();
  }

  public static void main(String... args) throws Exception {
    TestRunner runner = null;
    try {
      runner = new TestRunner();
      runner.runTests(args);
    } finally {
      if (nonNull(runner)) {
        runner.cleanup();
      }
    }
  }

  private void cleanup() throws Exception {
    ProjectDatabaseHelper.shutdown();
    String p = System.getProperty(TEMP_PROJECT_SETTING_DIR);
    org.apache.commons.io.FileUtils.deleteDirectory(new File(p));
  }

  private List<Class<?>> getTestClass(String testName) throws ClassNotFoundException {
    List<Class<?>> classes = new ArrayList<>();
    CachedASMReflector cachedASMReflector = CachedASMReflector.getInstance();
    for (ClassIndex classIndex : cachedASMReflector.getGlobalClassIndex().values()) {
      String fqcn = classIndex.getReturnType();
      String className = classIndex.getName();

      if (fqcn.equals(testName) || className.equals(testName) || fqcn.matches(testName)) {
        classes.add(Class.forName(fqcn));
      }
    }
    return classes;
  }

  public void runTests(String... args) throws ClassNotFoundException {
    for (String arg : args) {
      List<LauncherDiscoveryRequest> requests = collectTests(arg);
      if (requests.isEmpty()) {
        log.warn("test not found {}", (Object[]) args);
      }
      for (LauncherDiscoveryRequest request : requests) {
        this.runJunit(arg, request);
      }
    }
    System.exit(0);
  }

  private void runJunit(String arg, LauncherDiscoveryRequest request) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    System.out.println(String.format("Running %s", arg));
    System.out.println("");

    Launcher launcher = LauncherFactory.create();
    SummaryGeneratingListener listener = new SummaryGeneratingListener();

    launcher.execute(request, listener);
    TestExecutionSummary summary = listener.getSummary();


    this.runCnt += summary.getTestsFoundCount();
    this.failureCnt += summary.getTestsFailedCount();
    this.ignoreCnt += summary.getTestsSkippedCount();
    System.out.println("");

    if (summary.getTestsFailedCount() > 0) {
      System.out.println(
          String.format(
              "FAIL Tests run: %d, Failures: %d, Ignore: %d, Time elapsed: %s",
              summary.getTestsFoundCount(),
              summary.getTestsFailedCount(),
              summary.getTestsSkippedCount(),
              stopwatch.stop()));
      System.out.println("Failures:");
      for (Failure failure : summary.getFailures()) {
        System.out.println(failure.getTestIdentifier().getDisplayName());
        failure.getException().printStackTrace();
        System.out.println("");
      }
    } else {
      System.out.println(
          String.format(
              "Tests run: %d, Failures: %d, Ignore: %d, Time elapsed: %s",
              summary.getTestsFoundCount(),
              summary.getTestsFailedCount(),
              summary.getTestsSkippedCount(),
              stopwatch.stop()));
      System.out.println("Success");
    }

    System.out.println(Strings.repeat("-", 80));
  }

  private List<LauncherDiscoveryRequest> collectTests(String arg) throws ClassNotFoundException {
    List<LauncherDiscoveryRequest> requests = new ArrayList<>(1);

    if (arg.contains("#")) {
      List<String> strings = Splitter.on("#").splitToList(arg);
      List<Class<?>> classes = getTestClass(strings.get(0));
      for (Class<?> cls : classes) {
        LauncherDiscoveryRequest request =
          LauncherDiscoveryRequestBuilder
          .request()
          .selectors(selectMethod(cls, strings.get(1)))
          .build();
        requests.add(request);
      }
    } else {
      List<Class<?>> classes = getTestClass(arg);
      for (Class<?> cls : classes) {
        LauncherDiscoveryRequest request =
          LauncherDiscoveryRequestBuilder
          .request()
          .selectors(selectClass(cls))
          .build();
        requests.add(request);
      }
    }

    return requests;
  }
}
