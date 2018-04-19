package de.sebastianruziczka.cobolunit

import org.gradle.api.Project
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import de.sebastianruziczka.CobolExtension
import de.sebastianruziczka.api.CobolTestFramework
import de.sebastianruziczka.api.CobolUnitFrameworkProvider
import de.sebastianruziczka.buildcycle.test.TestFile
import de.sebastianruziczka.buildcycle.test.TestMethod
import de.sebastianruziczka.buildcycle.test.TestMethodResult
import de.sebastianruziczka.process.ProcessWrapper

@CobolUnitFrameworkProvider
class CobolUnit implements CobolTestFramework{
	Logger logger = LoggerFactory.getLogger('cobolUnit')

	private CobolExtension configuration
	private Project project
	private def defaultConf = ["ZUTZCWS", "SAMPLET"]
	private final static MAIN_FRAMEWORK_PROGRAMM =  'ZUTZCPC.CBL'
	private final static DEFAULT_CONF_NAME = 'DEFAULT.CONF'

	@Override
	void configure(CobolExtension configuration, Project project) {
		this.configuration = configuration
		this.project = project
	}

	@Override
	int prepare() {
		def files = [
			MAIN_FRAMEWORK_PROGRAMM,
			'ZUTZCPD.CPY',
			'ZUTZCWS.CPY'
		]
		String binFramworkPath = this.frameworkBin()+ '/'
		new File(binFramworkPath).mkdirs()
		logger.info('Moving sources of framwork into build')
		files.each{
			copy('res/' + it, binFramworkPath + it )
		}

		logger.info('Create default test.conf')
		this.createTestConf()

		logger.info('Start compiling cobol-unit test framework')
		return this.compileTestFramework(binFramworkPath, MAIN_FRAMEWORK_PROGRAMM)
	}

	private int compileTestFramework(String frameworkPath,String mainfile) {
		ProcessBuilder processBuilder=new ProcessBuilder('cobc', '-x', '-std=ibm', mainfile)
		def file = new File(frameworkPath)
		processBuilder.directory(file)
		logger.info('Framwork compile command args: ' + processBuilder.command().dump())

		ProcessWrapper processWrapper = new ProcessWrapper(processBuilder, 'FramweorkCompile', this.frameworkBin() + '/' + 'ZUTZCPC.LOG')
		return processWrapper.exec()
	}

	private void createTestConf() {
		String path = this.defaultConfPath()
		logger.info('Using Path: '+path)
		def defaultConfFile = new File(path)
		defaultConfFile.delete()

		defaultConfFile.withWriter { out ->
			this.defaultConf.each { out.println it }
		}
	}


	private String defaultConfPath() {
		return this.frameworkBin() + '/' + DEFAULT_CONF_NAME
	}

	private void copy(String source, String destination) {
		logger.info('COPY: '+ source + '>>' + destination)
		URLClassLoader urlClassLoader = (URLClassLoader) Thread.currentThread().getContextClassLoader()
		URL manifestUrl = urlClassLoader.findResource(source)
		InputStream is = manifestUrl.openStream()

		File targetFile = new File(destination)
		OutputStream outStream = new FileOutputStream(targetFile)

		byte[] buffer = new byte[1024]
		int length
		while ((length = is.read(buffer)) > 0) {
			outStream.write(buffer, 0, length)
		}

		outStream.close()
		is.close()
	}

	@Override
	public TestFile test(String srcName, String testName) {
		String srcModulePath = this.srcModuleOf(srcName)
		String testModulePath = this.testModuleOf(testName)

		logger.info('Preprocess Test: ' + testName)
		this.preprocessTest(srcName, testName, null)
		logger.info('Compile Test: ' + testName)
		this.compileTest(srcModulePath, testModulePath, testName)
		logger.info('Run Test: ' + testName)
		String result = this.executeTest(this.frameworkBinModuleOf(testName), this.getFileName(testName))

		return this.parseProcessOutput(result)
	}

	private TestFile parseProcessOutput(String processOutput) {
		String[] lines = processOutput.split(System.getProperty('line.separator'))
		if (!lines[1].equals('TEST SUITE:')){
			throw new IllegalArgumentException('Could not parse cobol unit test output');
		}

		TestFile testFile = new TestFile()
		for (int lineNumber = 3; lineNumber < lines.length; lineNumber ++) {
			if (lines[lineNumber].startsWith('     PASS:   ')) {
				String name = lines[lineNumber].substring('     PASS:   '.length())
				testFile.addTestMethod(new TestMethod(name, TestMethodResult.SUCCESSFUL, ''))
			}
			else if (lines[lineNumber].startsWith('**** FAIL:   ')) {
				String name = lines[lineNumber].substring('**** FAIL:   '.length())
				String compareResult = lines[lineNumber + 1]
				testFile.addTestMethod(new TestMethod(name, TestMethodResult.FAILED, compareResult))
			}
		}
		return testFile
	}

	private String executeTest(String binModulePath, String execName) {
		def logFilePath = binModulePath + '/' + 'TESTEXEC.LOG'

		ProcessBuilder processBuilder = new ProcessBuilder(binModulePath + '/' + execName)
		processBuilder.directory(new File(binModulePath))
		logger.info('Executing test file: '+ binModulePath + '/' + execName)

		ProcessWrapper processWrapper = new ProcessWrapper(processBuilder, 'Execute Unittest '+ execName, logFilePath)
		processWrapper.exec(true)
		return processWrapper.processOutput()
	}




	private String srcModuleOf(String relativePath) {
		String absolutePath = this.configuration.absoluteSrcMainPath(this.project) + '/' + relativePath
		return this.project.file(absolutePath).getParent()
	}

	private String testModuleOf(String relativePath) {
		String absolutePath = this.configuration.absoluteSrcTestPath(this.project) + '/' + relativePath
		return this.project.file(absolutePath).getParent()
	}

	private String frameworkBinModuleOf(String relativePath) {
		String absolutePath = this.frameworkBin() + '/' + relativePath
		return this.project.file(absolutePath).getParent()
	}

	private int compileTest(String srcModulePath, String testModulePath, String testName) {
		String precompiledTestPath = this.frameworkBin() + '/' + testName
		ProcessBuilder processBuilder = new ProcessBuilder('cobc', '-x', precompiledTestPath)
		def modulePath = this.frameworkBinModuleOf(testName)
		processBuilder.directory(new File(modulePath))
		def env = processBuilder.environment()
		String cobCopyEnvValue = srcModulePath + ':' + testModulePath
		env.put('COBCOPY', cobCopyEnvValue)
		logger.info('Compiling precompiled test')
		logger.info('Module path: ' + modulePath)
		logger.info('Precompiled test path: ' + precompiledTestPath)
		logger.info('ENV: ' + env)

		def logPath = modulePath+ '/' + this.getFileName(testName) + '_' + 'TESTCOMPILE.LOG'
		ProcessWrapper processWrapper = new ProcessWrapper(processBuilder, 'Compile UnitTest '+ testName, logPath)

		return processWrapper.exec()
	}

	private String frameworkBin() {
		return this.configuration.absoluteUnitTestFramworkPath(this.project, this.getClass().getSimpleName())
	}

	private int preprocessTest(String mainFile, String testFile, String testConfig) {
		String zutzcpcPath = this.frameworkBin() + '/' + this.getFileName(MAIN_FRAMEWORK_PROGRAMM)
		ProcessBuilder processBuilder = new ProcessBuilder(zutzcpcPath)

		def env = processBuilder.environment()
		env.put('SRCPRG', this.configuration.absoluteSrcMainModulePath(this.project)+ '/'+ mainFile)
		env.put('TESTPRG', this.frameworkBin() + '/' + testFile)
		env.put('TESTNAME', this.getFileName(testFile))
		if (testConfig == null) {
			env.put('UTSTCFG', this.defaultConfPath())
		}else {
			env.put('UTSTCFG', testConfig)
		}
		String copybooks = this.getParent(mainFile) + ':' + this.getParent(testFile)
		env.put('COBCOPY', copybooks)
		env.put('UTESTS', this.configuration.absoluteSrcTestPath(this.project) + '/' + testFile)

		logger.info('Environment: ' + env.dump())
		logger.info('Test precompile command args: ' + processBuilder.command().dump())

		def logPath = this.frameworkBinModuleOf(mainFile) + '/' + this.getFileName(testFile) + '_' + 'PRECOMPILER.LOG'
		ProcessWrapper processWrapper = new ProcessWrapper(processBuilder, 'Preprocess UnitTest '+ testFile, logPath)
		return processWrapper.exec()
	}

	private String getFileName(String path) {
		File file = new File(path)
		String name = file.getName()
		if (name.contains(".")) {
			name = name.split("\\.")[0]
		}
		return name
	}

	private String getParent(String path) {
		File file = new File(path)
		return file.getParent()
	}
}
