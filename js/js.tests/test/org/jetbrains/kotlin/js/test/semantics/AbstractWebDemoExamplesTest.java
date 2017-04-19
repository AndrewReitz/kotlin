/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.test.semantics;

import com.google.common.collect.Lists;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.backend.common.output.OutputFileCollection;
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport;
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;
import org.jetbrains.kotlin.cli.common.output.outputUtils.OutputUtilsKt;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.idea.KotlinFileType;
import org.jetbrains.kotlin.js.JavaScript;
import org.jetbrains.kotlin.js.backend.ast.JsProgram;
import org.jetbrains.kotlin.js.config.EcmaVersion;
import org.jetbrains.kotlin.js.config.JSConfigurationKeys;
import org.jetbrains.kotlin.js.config.JsConfig;
import org.jetbrains.kotlin.js.facade.K2JSTranslator;
import org.jetbrains.kotlin.js.facade.MainCallParameters;
import org.jetbrains.kotlin.js.facade.TranslationResult;
import org.jetbrains.kotlin.js.test.rhino.RhinoResultChecker;
import org.jetbrains.kotlin.js.test.rhino.RhinoSystemOutputChecker;
import org.jetbrains.kotlin.js.test.utils.DirectiveTestUtils;
import org.jetbrains.kotlin.js.test.utils.JsTestUtils;
import org.jetbrains.kotlin.js.test.utils.JsVerificationKt;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.KotlinTestWithEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.kotlin.js.test.rhino.RhinoUtils.runRhinoTest;
import static org.jetbrains.kotlin.js.test.utils.JsTestUtils.convertFileNameToDotJsFile;
import static org.jetbrains.kotlin.js.test.utils.JsTestUtils.readFile;
import static org.jetbrains.kotlin.test.InTextDirectivesUtils.isDirectiveDefined;
import static org.jetbrains.kotlin.utils.PathUtil.getKotlinPathsForDistDirectory;

public abstract class AbstractWebDemoExamplesTest extends KotlinTestWithEnvironment {
    // predictable order of ecma version in tests
    private static final String TEST_DATA_DIR_PATH = "js/js.translator/testData/";

    private static final String COMMON_FILES_DIR = "_commonFiles/";

    private static final String TEST_MODULE = "JS_TESTS";
    private static final String NO_INLINE_DIRECTIVE = "// NO_INLINE";

    @NotNull
    private String relativePathToTestDir = "";

    @SuppressWarnings("JUnitTestCaseWithNonTrivialConstructors")
    public AbstractWebDemoExamplesTest(@NotNull String relativePathToTestDir) {
        this.relativePathToTestDir = relativePathToTestDir;
    }

    @Override
    protected KotlinCoreEnvironment createEnvironment() {
        return KotlinCoreEnvironment.createForTests(getTestRootDisposable(), new CompilerConfiguration(), EnvironmentConfigFiles.JS_CONFIG_FILES);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        File outDir = new File(getOutputPath());

        KotlinTestUtils.mkdirs(outDir);
    }

    private void generateJavaScriptFiles(
            @NotNull String kotlinFilePath,
            @NotNull MainCallParameters mainCallParameters
    ) throws Exception {
        Project project = getProject();
        List<String> allFiles = withAdditionalKotlinFiles(Collections.singletonList(kotlinFilePath));
        List<KtFile> jetFiles = createJetFileList(project, allFiles, null);

        JsConfig config = createConfig(getProject(), TEST_MODULE, EcmaVersion.v5, null, jetFiles);
        File outputFile = new File(getOutputFilePath(getBaseName(kotlinFilePath), EcmaVersion.v5));

        translateFiles(jetFiles, outputFile, mainCallParameters, config);
    }

    private static void translateFiles(
            @NotNull List<KtFile> jetFiles,
            @NotNull File outputFile,
            @NotNull MainCallParameters mainCallParameters,
            @NotNull JsConfig config
    ) throws Exception {
        K2JSTranslator translator = new K2JSTranslator(config);
        TranslationResult translationResult = translator.translate(jetFiles, mainCallParameters);

        if (!(translationResult instanceof TranslationResult.Success)) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PrintingMessageCollector collector = new PrintingMessageCollector(
                    new PrintStream(outputStream),
                    MessageRenderer.PLAIN_FULL_PATHS,
                    true
            );
            AnalyzerWithCompilerReport.Companion.reportDiagnostics(translationResult.getDiagnostics(), collector);
            String messages = new String(outputStream.toByteArray(), "UTF-8");
            throw new AssertionError("The following errors occurred compiling test:\n" + messages);
        }

        TranslationResult.Success successResult = (TranslationResult.Success) translationResult;

        OutputFileCollection outputFiles = successResult.getOutputFiles(outputFile, null, null);
        File outputDir = outputFile.getParentFile();
        assert outputDir != null : "Parent file for output file should not be null, outputFilePath: " + outputFile.getPath();
        OutputUtilsKt.writeAllTo(outputFiles, outputDir);

        processJsProgram(successResult.getProgram(), jetFiles);
    }

    private static void processJsProgram(@NotNull JsProgram program, @NotNull List<KtFile> jetFiles) throws Exception {
        for (KtFile file : jetFiles) {
            String text = file.getText();
            DirectiveTestUtils.processDirectives(program, text);
        }
        JsVerificationKt.verifyAst(program);
    }

    private void runRhinoTests(@NotNull String testName, @NotNull RhinoResultChecker checker
    ) throws Exception {
            runRhinoTest(withAdditionalJsFiles(getOutputFilePath(testName, EcmaVersion.v5)), checker, null, EcmaVersion.v5);
    }

    // helpers

    @NotNull
    private String pathToTestDir() {
        return TEST_DATA_DIR_PATH + relativePathToTestDir;
    }

    @NotNull
    private String getOutputFilePath(@NotNull String testName, @NotNull EcmaVersion ecmaVersion) {
        return getOutputPath() + convertFileNameToDotJsFile(testName, ecmaVersion);
    }

    @NotNull
    private String getInputFilePath(@NotNull String filename) {
        return (pathToTestDir() + "cases/") + filename;
    }

    @NotNull
    private String expectedFilePath(@NotNull String testName) {
        return (pathToTestDir() + "expected/") + testName + ".out";
    }

    @NotNull
    private JsConfig createConfig(
            @NotNull Project project,
            @NotNull String moduleName,
            @NotNull EcmaVersion ecmaVersion,
            @Nullable List<String> libraries,
            @NotNull List<KtFile> files
    ) {
        CompilerConfiguration configuration = getEnvironment().getConfiguration().copy();

        configuration.put(CommonConfigurationKeys.DISABLE_INLINE, hasNoInline(files));

        List<String> librariesWithStdlib = new ArrayList<>(JsConfig.JS_STDLIB);
        if (libraries != null) {
            librariesWithStdlib.addAll(libraries);
        }
        librariesWithStdlib.add(getKotlinPathsForDistDirectory().getJsKotlinTestJarPath().getAbsolutePath());

        configuration.put(JSConfigurationKeys.LIBRARIES, librariesWithStdlib);

        configuration.put(CommonConfigurationKeys.MODULE_NAME, moduleName);
        configuration.put(JSConfigurationKeys.TARGET, ecmaVersion);

        configuration.put(JSConfigurationKeys.SOURCE_MAP, false);
        configuration.put(JSConfigurationKeys.META_INFO, false);

        return new JsConfig(project, configuration);
    }

    private static boolean hasNoInline(@NotNull List<KtFile> files) {
        for (KtFile file : files) {
            if (isDirectiveDefined(file.getText(), NO_INLINE_DIRECTIVE)) {
                return true;
            }
        }

        return false;
    }

    @NotNull
    private String getOutputPath() {
        return pathToTestDir() + "out/";
    }

    @NotNull
    private List<String> withAdditionalKotlinFiles(@NotNull List<String> files) {
        List<String> result = Lists.newArrayList(files);

        // add all kotlin files from testData/_commonFiles
        result.addAll(JsTestUtils.getFilesInDirectoryByExtension(TEST_DATA_DIR_PATH + COMMON_FILES_DIR, KotlinFileType.EXTENSION));
        // add all kotlin files from <testDir>/_commonFiles
        result.addAll(JsTestUtils.getFilesInDirectoryByExtension(pathToTestDir() + COMMON_FILES_DIR, KotlinFileType.EXTENSION));

        return result;
    }

    @NotNull
    private List<String> withAdditionalJsFiles(@NotNull String inputFile) {
        List<String> allFiles = Lists.newArrayList();

        // add all js files from testData/_commonFiles
        allFiles.addAll(JsTestUtils.getFilesInDirectoryByExtension(TEST_DATA_DIR_PATH + COMMON_FILES_DIR, JavaScript.EXTENSION));
        // add all js files from <testDir>/_commonFiles
        allFiles.addAll(JsTestUtils.getFilesInDirectoryByExtension(pathToTestDir() + COMMON_FILES_DIR, JavaScript.EXTENSION));

        // add <testDir>/cases/<testName>.js if it exists
        String jsFilePath = getInputFilePath(getTestName(true) + JavaScript.DOT_EXTENSION);
        File jsFile = new File(jsFilePath);
        if (jsFile.exists() && jsFile.isFile()) {
            allFiles.add(jsFilePath);
        }

        allFiles.add(inputFile);
        return allFiles;
    }

    private static List<KtFile> createJetFileList(@NotNull Project project, @NotNull List<String> list, @Nullable String root) {
        List<KtFile> libFiles = Lists.newArrayList();

        PsiManager psiManager = PsiManager.getInstance(project);
        VirtualFileSystem fileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL);

        VirtualFile rootFile = root == null ? null : fileSystem.findFileByPath(root);

        for (String libFileName : list) {
            VirtualFile virtualFile = rootFile == null ? fileSystem.findFileByPath(libFileName) : rootFile.findFileByRelativePath(libFileName);
            //TODO logging?
            assert virtualFile != null : "virtual file is missing, most likely the file doesn't exist: " + libFileName;
            PsiFile psiFile = psiManager.findFile(virtualFile);
            libFiles.add((KtFile) psiFile);
        }
        return libFiles;
    }

    private static String getBaseName(String path) {
        String systemIndependentPath = FileUtil.toSystemIndependentName(path);

        int start = systemIndependentPath.lastIndexOf("/");
        if (start == -1) {
            start = 0;
        }

        int end = systemIndependentPath.lastIndexOf(".");
        if (end == -1) {
            end = path.length();
        }

        return path.substring(start, end);
    }

    protected void checkOutput(@NotNull String kotlinFilename,
            @NotNull String expectedResult,
            @NotNull String... args) throws Exception {
        generateJavaScriptFiles(getInputFilePath(kotlinFilename), MainCallParameters.mainWithArguments(Lists.newArrayList(args)));
        runRhinoTests(getBaseName(kotlinFilename), new RhinoSystemOutputChecker(expectedResult));
    }

    protected void performTestWithMain(@NotNull String testName, @NotNull String testId, @NotNull String... args) throws Exception {
        generateJavaScriptFiles(getInputFilePath(testName + ".kt"), MainCallParameters.mainWithArguments(Lists.newArrayList(args)));
        runRhinoTests(getBaseName(testName + ".kt"), new RhinoSystemOutputChecker(
                readFile(expectedFilePath(testName + testId))));
    }
}
