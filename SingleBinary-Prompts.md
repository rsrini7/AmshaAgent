You are implementing a "true single binary" upgrade for ArcClaw, a Quarkus 
GraalVM native image project. The goal is to eliminate run.sh and run.ps1 
entirely so the binary is fully self-contained: it holds the vec0 native libs 
(already done), the .env.example, and all UI HTML files (already embedded via 
META-INF/resources), and handles everything those shell scripts did — .env 
loading, LD_PRELOAD on Linux, and default path resolution — entirely in Java.

────────────────────────────────────────────────────────────────────────────────
CONTEXT: WHAT THE BINARY ALREADY DOES
────────────────────────────────────────────────────────────────────────────────

1. Vec0 extraction: Vec0NativeLibraryInstaller.java fires at priority 10 before
   CDI boots. It reads the OS/arch, extracts the matching vec0 binary from 
   classpath (native/vec0/vec0-linux-x86_64.so etc.) to ./lib/vec0.so next to 
   the binary, and sets the ARCCLAWSQLITEVECPATH system property.

2. UI serving: All HTML/JS/CSS from arcclaw-ui/dist is copied into
   arcclaw-app/src/main/resources/META-INF/resources/ and served by Quarkus 
   HTTP at runtime. Already embedded. No changes needed.

3. ArcClawCli.main() already calls Vec0NativeLibraryInstaller.performInstallation()
   before Quarkus.run(). The new BinaryBootstrap.bootstrap(args) must be called
   BEFORE that vec0 call.

────────────────────────────────────────────────────────────────────────────────
CONTEXT: WHAT run.sh / run.ps1 CURRENTLY DO (to be replaced by Java)
────────────────────────────────────────────────────────────────────────────────

1. Resolve SCRIPTDIR = directory of the script/binary
2. Load .env from SCRIPTDIR line by line → export KEY=VALUE
3. Set defaults:
     ARCCLAWSQLITEVECPATH=${ARCCLAWSQLITEVECPATH:-.lib/vec0}  (note: lib not .lib)
     ARCCLAWDATADIR=${ARCCLAWDATADIR:-.data}                  (note: data not .data)
4. Linux only: export LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6
5. exec the binary with original args

────────────────────────────────────────────────────────────────────────────────
TASK 1 — CREATE BinaryBootstrap.java
────────────────────────────────────────────────────────────────────────────────

File: arcclaw-app/src/main/java/com/arcclaw/app/config/BinaryBootstrap.java

Rules:
- No CDI annotations (@ApplicationScoped, @Inject, etc.) — this is plain Java, 
  called before Quarkus starts.
- No Quarkus imports.
- Must compile and run correctly in GraalVM native image.

Implement the following static methods:

  public static void bootstrap(String[] args)
    Calls in this exact order:
      1. reexecWithLdPreloadIfNeeded(binaryDir, args)
      2. loadDotEnv(binaryDir)
      3. extractEnvExampleIfNeeded(binaryDir)
      4. setDefaultIfAbsent("ARCCLAW_RAG_VEC_PATH",  binaryDir + "/lib/vec0")
      5. setDefaultIfAbsent("ARCCLAW_DATA_DIR",       binaryDir + "/data")
    Where binaryDir = resolveBinaryDir()

  private static Path resolveBinaryDir()
    Priority order:
      1. ProcessHandle.current().info().command() → toAbsolutePath().getParent()
         only if that path actually exists on disk
      2. /proc/self/exe → toRealPath().getParent() (Linux only, wrapped in try/catch)
      3. Fallback: Path.of("").toAbsolutePath()

  private static void reexecWithLdPreloadIfNeeded(Path binaryDir, String[] args)
    - Skip entirely if OS is not Linux (check os.name lowercase contains "linux")
    - Skip if env var ARCCLAW_LD_PRELOAD_SET is already set (prevents infinite loop)
    - Resolve libm path: try these candidates in order, return first that exists:
        /lib/x86_64-linux-gnu/libm.so.6
        /lib64/libm.so.6
        /usr/lib/x86_64-linux-gnu/libm.so.6
        /lib/aarch64-linux-gnu/libm.so.6
      If none found, log a warning to stderr and return (don't crash).
    - Get own executable path from ProcessHandle.current().info().command()
      If empty, return silently.
    - Build command: [ownPath] + args
    - ProcessBuilder pb = new ProcessBuilder(command)
      pb.environment().put("LD_PRELOAD", libmPath)
      pb.environment().put("ARCCLAW_LD_PRELOAD_SET", "1")
      pb.inheritIO()
    - Start process, System.exit(process.waitFor())
    - Wrap in try/catch Exception: on failure print warning to stderr and return
      (do NOT crash — newer GraalVM may not need LD_PRELOAD at all)

  static void loadDotEnv(Path dir)
    (package-private for testability)
    - Resolve dir/.env — if not exists, return silently
    - Read line by line with BufferedReader
    - Strip \r (Windows CRLF), skip blank lines and lines starting with #
    - Parse KEY=VALUE with this regex: ^\s*([A-Za-z_][A-Za-z0-9_.]*?)\s*=\s*(.*?)\s*$
    - Strip inline comments: if value contains " #" or " #" treat everything
      after the first unquoted # as a comment. Simple rule: if value starts with
      a quote character, keep the full value; otherwise trim at first " #".
    - Only set if BOTH System.getenv(key) == null AND System.getProperty(key) == null
    - Use System.setProperty(key, value)
    - Wrap IOException in a stderr warning, never throw

  private static void extractEnvExampleIfNeeded(Path dir)
    - If dir/.env exists → return immediately (already configured)
    - If dir/.env.example does NOT exist:
        Load /env-example from classpath via 
        BinaryBootstrap.class.getResourceAsStream("/env-example")
        If stream is null → log to stderr "env-example not found in classpath" → return
        Copy stream to dir/.env.example using Files.copy with REPLACE_EXISTING
        Print to stderr:
          "[ArcClaw] No .env found. Created .env.example next to the binary."
          "[ArcClaw] Copy and configure it:  cp .env.example .env"
    - If dir/.env.example already exists → just print the cp hint above, no copy

  private static void setDefaultIfAbsent(String key, String defaultValue)
    - If System.getenv(key) == null AND System.getProperty(key) == null
      → System.setProperty(key, defaultValue)

────────────────────────────────────────────────────────────────────────────────
TASK 2 — WIRE INTO ArcClawCli.java
────────────────────────────────────────────────────────────────────────────────

In ArcClawCli.main(String[] args), add as the VERY FIRST statement, before 
anything else including the vec0 installation block:

    BinaryBootstrap.bootstrap(args);

The full order in main() must be:
  1. BinaryBootstrap.bootstrap(args)       ← NEW (may System.exit on Linux re-exec)
  2. Vec0NativeLibraryInstaller call       ← existing, unchanged
  3. Quarkus.run(...)                      ← existing, unchanged

Do not change any other logic in ArcClawCli.java.

────────────────────────────────────────────────────────────────────────────────
TASK 3 — EMBED env-example AS A CLASSPATH RESOURCE
────────────────────────────────────────────────────────────────────────────────

Copy the root-level .env.example to:
  arcclaw-app/src/main/resources/env-example
  (no dot prefix, no extension — just "env-example")

In arcclaw-app/pom.xml, add a maven-resources-plugin execution to keep it 
in sync automatically (so devs never have to manually copy it):

  <plugin>
    <artifactId>maven-resources-plugin</artifactId>
    <executions>
      <execution>
        <id>sync-env-example</id>
        <phase>generate-resources</phase>
        <goals><goal>copy-resources</goal></goals>
        <configuration>
          <outputDirectory>${project.basedir}/src/main/resources</outputDirectory>
          <overwrite>true</overwrite>
          <resources>
            <resource>
              <directory>${project.basedir}/../</directory>
              <includes>
                <include>.env.example</include>
              </includes>
              <filtering>false</filtering>
            </resource>
          </resources>
        </configuration>
      </execution>
    </executions>
  </plugin>

Note: maven-resources-plugin copies the file preserving the name ".env.example".
After copy it lands at src/main/resources/.env.example but the classpath resource
name will be "/.env.example" not "/env-example". 

To get it as "/env-example" (no dot, friendlier to classpath loaders), use a
Maven Antrun execution instead:

  <plugin>
    <artifactId>maven-antrun-plugin</artifactId>
    <executions>
      <execution>
        <id>sync-env-example</id>
        <phase>generate-resources</phase>
        <goals><goal>run</goal></goals>
        <configuration>
          <target>
            <copy file="${project.basedir}/../.env.example"
                  tofile="${project.basedir}/src/main/resources/env-example"
                  overwrite="true"/>
          </target>
        </configuration>
      </execution>
    </executions>
  </plugin>

Use the Antrun approach since we need the exact filename "env-example".

────────────────────────────────────────────────────────────────────────────────
TASK 4 — UPDATE application.yaml (two lines only)
────────────────────────────────────────────────────────────────────────────────

Find the existing native.resources.includes list:

  native:
    resources:
      includes:
        - "META-INF/services/*"
        - "META-INF/native-image/**"
        - "skills/index.txt"
        - "skills/**.md"

Append exactly these two entries (keep existing entries untouched):

        - "env-example"
        - "native/vec0/**"

The vec0 line covers: vec0-linux-x86_64.so, vec0-macos-aarch64.dylib,
vec0-windows-x86_64.dll, and any future platform variants.

────────────────────────────────────────────────────────────────────────────────
TASK 5 — UPDATE mise.toml dist and distpack tasks
────────────────────────────────────────────────────────────────────────────────

In tasks.dist:
  REMOVE: Copy-Item .env.example distDir/.env.example
  REMOVE: The entire run.sh template generation block 
          (System.IO.File.ReadAllText .mise/launchers/run.sh.template ...)
  REMOVE: Copy-Item .mise/launchers/run.ps1.template distDir/run.ps1
  KEEP:   Binary copy (Copy-Item src distDir/exeName)
  KEEP:   vec0 copy into distDir/lib/ from src/main/resources/native/vec0/
  UPDATE the success message to:
    Write-Host "dist assembled — binary is self-contained."
    Write-Host "Run directly (no run.sh needed):"
    Write-Host "  Linux/macOS : ./arcclaw-app-1.0.0-SNAPSHOT-runner serve"
    Write-Host "  Windows     : .\arcclaw-app-1.0.0-SNAPSHOT-runner.exe serve"
    Write-Host "On first run with no .env present, .env.example is auto-extracted."

In tasks.distpack:
  REMOVE: Copy-Item dist/.env.example stage/.env.example  (all 3 platform blocks)
  REMOVE: Copy-Item dist/run.sh stage/run.sh  (all 3 platform blocks)
  REMOVE: run.ps1 content replacement blocks
  KEEP:   Binary copy into stage/
  KEEP:   vec0 copy into stage/lib/
  KEEP:   tar/zip archive creation

In tasks.buildnative and tasks.buildnative-local:
  ADD "buildui-copy" as a dependency so UI is always fresh before native compile:
    depends = ["envrecover", "buildui-copy"]

────────────────────────────────────────────────────────────────────────────────
TASK 6 — CLEANUP (optional but clean)
────────────────────────────────────────────────────────────────────────────────

The following files become dead code after this change. Do NOT delete them yet 
(keep for one release cycle as fallback) but add a deprecation comment at the 
top of each:

  .mise/launchers/run.sh.template   → # DEPRECATED: replaced by BinaryBootstrap.java
  .mise/launchers/run.ps1.template  → # DEPRECATED: replaced by BinaryBootstrap.java

────────────────────────────────────────────────────────────────────────────────
TASK 7 — UNIT TEST FOR BinaryBootstrap
────────────────────────────────────────────────────────────────────────────────

Create:
  arcclaw-app/src/test/java/com/arcclaw/app/config/BinaryBootstrapTest.java

Test cases (plain JUnit 5, no Quarkus test infrastructure needed):

  testLoadDotEnv_setsSystemProperties()
    - Write a temp .env file with:
        KEY_A=hello
        KEY_B=world
        # comment line
        KEY_C=value with spaces
        KEY_D=value#not-a-comment
        KEY_E="quoted#value"
    - Call BinaryBootstrap.loadDotEnv(tempDir)
    - Assert System.getProperty("KEY_A") == "hello"
    - Assert System.getProperty("KEY_B") == "world"
    - Assert System.getProperty("KEY_C") == "value with spaces"
    - Assert inline comment stripping: KEY_D should be "value" (unquoted, # strips)
    - Assert quoted value preserved: KEY_E should be "quoted#value"
    - Clean up system properties in @AfterEach

  testLoadDotEnv_doesNotOverwriteExistingEnv()
    - System.setProperty("KEY_A", "original")
    - Write temp .env with KEY_A=overridden
    - Call loadDotEnv
    - Assert System.getProperty("KEY_A") == "original"
    - Clean up in @AfterEach

  testLoadDotEnv_missingFile_doesNotThrow()
    - Call BinaryBootstrap.loadDotEnv(Path.of("/nonexistent/path"))
    - Assert no exception thrown

────────────────────────────────────────────────────────────────────────────────
CONSTRAINTS & NOTES
────────────────────────────────────────────────────────────────────────────────

- Java version: 25 (temurin-25 / graalvm-community-25.0.2)
- Quarkus version: 3.23.0+
- Do not use Quarkus or CDI APIs in BinaryBootstrap — it must be pure Java
- Do not use reflection APIs not already registered for native image
- ProcessHandle is available in GraalVM native — use it freely
- Files.copy, BufferedReader, Path — all safe in native image
- System.setProperty is safe in native image and is read by Quarkus 
  MicroProfile Config at startup since it uses the system properties 
  ConfigSource (ordinal 400, highest priority)
- The env var names use underscores (ARCCLAW_DATA_DIR) matching the .env file
  but Quarkus maps these to dotted config keys automatically via MicroProfile
  Config's env var name conversion rules
- Do not rename or restructure any existing class other than ArcClawCli.main()
- The arcclaw-core, arcclaw-rag, and other sibling modules are NOT touched
- After all changes, the only file a user needs to distribute is the binary itself.
  The lib/vec0.* files are still extracted to ./lib/ at runtime (Vec0NativeLibraryInstaller
  handles this). The binary dir and lib dir must be writable on first run.