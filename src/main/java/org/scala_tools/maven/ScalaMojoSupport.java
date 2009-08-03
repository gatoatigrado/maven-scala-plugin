/*
 * Copyright 2007 scala-tools.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.scala_tools.maven;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.artifact.InvalidDependencyVersionException;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.filter.AncestorOrSelfDependencyNodeFilter;
import org.apache.maven.shared.dependency.tree.filter.AndDependencyNodeFilter;
import org.apache.maven.shared.dependency.tree.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.tree.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.tree.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.tree.traversal.FilteringDependencyNodeVisitor;
import org.codehaus.plexus.util.StringUtils;
import org.scala_tools.maven.dependency.CheckScalaVersionVisitor;
import org.scala_tools.maven.dependency.ScalaDistroArtifactFilter;
import org.scala_tools.maven.executions.JavaCommand;
import org.scala_tools.maven.executions.JavaMainCaller;
import org.scala_tools.maven.executions.ReflectionJavaMainCaller;
import org.scala_tools.maven.executions.ScalaCommandWIthArgsInFile;

abstract class ScalaMojoSupport extends AbstractMojo {

    public static final String SCALA_GROUPID= "org.scala-lang";
    public static final String SCALA_LIBRARY_ARTIFACTID= "scala-library";
    /**
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * Used to look up Artifacts in the remote repository.
     *
     * @component
     * @required
     * @readonly
     */
    protected ArtifactFactory factory;

    /**
     * Used to look up Artifacts in the remote repository.
     *
     * @component
     * @required
     * @readonly
     */
    protected ArtifactResolver resolver;
    /**
     * Location of the local repository.
     *
     * @parameter expression="${localRepository}"
     * @readonly
     * @required
     */
    protected ArtifactRepository localRepo;

    /**
     * List of Remote Repositories used by the resolver
     *
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @readonly
     * @required
     */
    protected List<?> remoteRepos;

    /**
     * Additional dependencies/jar to add to classpath to run "scalaClassName" (scope and optional field not supported)
     * ex :
     * <pre>
     *    &lt;dependencies>
     *      &lt;dependency>
     *        &lt;groupId>org.scala-tools&lt;/groupId>
     *        &lt;artifactId>scala-compiler-addon&lt;/artifactId>
     *        &lt;version>1.0-SNAPSHOT&lt;/version>
     *      &lt;/dependency>
     *    &lt;/dependencies>
     * </pre>
     * @parameter
     */
    protected BasicArtifact[] dependencies;

    /**
     * Compiler plugin dependencies to use when compiling.
     * ex:
     * @parameter
     * <xmp>
     * <compilerPlugins>
     * <compilerPlugin>
     * <groupId>my.scala.plugin</groupId>
     * <artifactId>amazingPlugin</artifactId>
     * <version>1.0-SNAPSHOT</version>
     * </compilerPlugin>
     * </compilerPlugins>
     * </xmp>
     */
    protected BasicArtifact[] compilerPlugins;

    /**
     * Jvm Arguments.
     *
     * @parameter
     */
    protected String[] jvmArgs;

    /**
     * compiler additionnals arguments
     *
     * @parameter
     */
    protected String[] args;

    /**
     * className (FQN) of the scala tool to provide as
     *
     * @required
     * @parameter expression="${maven.scala.className}"
     *            default-value="scala.tools.nsc.Main"
     */
    protected String scalaClassName;

    /**
     * Scala 's version to use
     * @parameter expression="${maven.scala.version}"
     */
    protected String scalaVersion;

    /**
     * Display the command line called ?
     *
     * @required
     * @parameter expression="${maven.scala.displayCmd}"
     *            default-value="false"
     */
    protected boolean displayCmd;

    /**
     * Forks the execution of scalac into a separate process.
     *
     * @parameter default-value="true"
     */
    protected boolean fork = true;

    /**
     * Force the use of an external ArgFile to run any forked process.
     *
     * @parameter default-value="false"
     */
    protected boolean forceUseArgFile = false;

    /**
     * Check if every dependencies use the same version of scala-library.
     *
     * @parameter expression="${maven.scala.checkConsistency}" default-value="true"
     */
    protected boolean checkMultipleScalaVersions;

    /**
     * Determines if a detection of multiple scala versions in the dependencies will cause the build to fail.
     *
     * @parameter default-value="false"
     */
    protected boolean failOnMultipleScalaVersions = false;
    /**
     * Artifact factory, needed to download source jars.
     *
     * @component
     * @required
     * @readonly
     */
    protected MavenProjectBuilder mavenProjectBuilder;

    /**
     * The artifact repository to use.
     *
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    private ArtifactRepository localRepository;

    /**
     * The artifact factory to use.
     *
     * @component
     * @required
     * @readonly
     */
    private ArtifactFactory artifactFactory;

    /**
     * The artifact metadata source to use.
     *
     * @component
     * @required
     * @readonly
     */
    private ArtifactMetadataSource artifactMetadataSource;

    /**
     * The artifact collector to use.
     *
     * @component
     * @required
     * @readonly
     */
    private ArtifactCollector artifactCollector;

    /**
     * The dependency tree builder to use.
     *
     * @component
     * @required
     * @readonly
     */
    private DependencyTreeBuilder dependencyTreeBuilder;

    /**
     * This method resolves the dependency artifacts from the project.
     *
     * @param theProject The POM.
     * @return resolved set of dependency artifacts.
     *
     * @throws ArtifactResolutionException
     * @throws ArtifactNotFoundException
     * @throws InvalidDependencyVersionException
     */
    @SuppressWarnings("unchecked")
    protected Set<Artifact> resolveDependencyArtifacts(MavenProject theProject) throws Exception {
        AndArtifactFilter filter = new AndArtifactFilter();
        filter.add(new ScopeArtifactFilter(Artifact.SCOPE_TEST));
        filter.add(new ArtifactFilter(){
            public boolean include(Artifact artifact) {
                return !artifact.isOptional();
            }
        });
        //TODO follow the dependenciesManagement and override rules
        Set<Artifact> artifacts = theProject.createArtifacts(factory, Artifact.SCOPE_RUNTIME, filter);
        for (Artifact artifact : artifacts) {
            resolver.resolve(artifact, remoteRepos, localRepo);
        }
        return artifacts;
    }

    /**
     * This method resolves all transitive dependencies of an artifact.
     *
     * @param artifact the artifact used to retrieve dependencies
     *
     * @return resolved set of dependencies
     *
     * @throws ArtifactResolutionException
     * @throws ArtifactNotFoundException
     * @throws ProjectBuildingException
     * @throws InvalidDependencyVersionException
     */
    protected Set<Artifact> resolveArtifactDependencies(Artifact artifact) throws Exception {
        Artifact pomArtifact = factory.createArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), "", "pom");
        MavenProject pomProject = mavenProjectBuilder.buildFromRepository(pomArtifact, remoteRepos, localRepo);
        return resolveDependencyArtifacts(pomProject);
    }

    protected void addToClasspath(String groupId, String artifactId, String version, Set<String> classpath) throws Exception {
        addToClasspath(groupId, artifactId, version, classpath, classpath);
    }

    protected void addToClasspath(String groupId, String artifactId, String version, Set<String> classpath, Set<String> dependencies) throws Exception {
        addToClasspath(factory.createArtifact(groupId, artifactId, version, Artifact.SCOPE_RUNTIME, "jar"), classpath, dependencies);
    }

    /**
     * @param dependencies
     *            set to add all dependencies to. pass $classpath$ to add both
     *            to the same set, or null to not add dependencies.
     */
    protected void addToClasspath(Artifact artifact, Set<String> classpath,
            Set<String> dependencies) throws Exception
    {
        resolver.resolve(artifact, remoteRepos, localRepo);
        classpath.add(artifact.getFile().getCanonicalPath());
        if (dependencies != null) {
            for (Artifact dep : resolveArtifactDependencies(artifact)) {
                dependencies.add(dep.getFile().getCanonicalPath());
            }
        }
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            checkScalaVersion();
            doExecute();
        } catch (MojoExecutionException exc) {
            throw exc;
        } catch (MojoFailureException exc) {
            throw exc;
        } catch (RuntimeException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new MojoExecutionException("wrap: " + exc, exc);
        }
    }

    @SuppressWarnings("unchecked")
    protected List<Dependency> getDependencies() {
        return project.getCompileDependencies();
    }

    @SuppressWarnings("unchecked")
    protected void checkScalaVersion() throws Exception {
        String detectedScalaVersion = null;
        for (Dependency dep : getDependencies()) {
            if (SCALA_GROUPID.equals(dep.getGroupId()) && SCALA_LIBRARY_ARTIFACTID.equals(dep.getArtifactId())) {
                detectedScalaVersion = dep.getVersion();
            }
        }
        if (StringUtils.isEmpty(detectedScalaVersion)) {
            List<Dependency> deps = new ArrayList<Dependency>();
            deps.addAll(project.getModel().getDependencies());
            if (project.getModel().getDependencyManagement() != null) {
                deps.addAll(project.getModel().getDependencyManagement().getDependencies());
            }
            for (Dependency dep : deps) {
                if (SCALA_GROUPID.equals(dep.getGroupId()) && SCALA_LIBRARY_ARTIFACTID.equals(dep.getArtifactId())) {
                    detectedScalaVersion = dep.getVersion();
                }
            }
        }

        if (StringUtils.isEmpty(detectedScalaVersion)) {
            if (!"pom".equals( project.getPackaging().toLowerCase() )) {
                getLog().warn("you don't define "+SCALA_GROUPID + ":" + SCALA_LIBRARY_ARTIFACTID + " as a dependency of the project");
            }
        } else {
            // grappy hack to retrieve the SNAPSHOT version without timestamp,...
            // because if version is -SNAPSHOT and artifact is deploy with uniqueValue then the version
            // get from dependency is with the timestamp and a build number (the resolved version)
            // but scala-compiler with the same version could have different resolved version (timestamp,...)
            boolean isSnapshot = ArtifactUtils.isSnapshot(detectedScalaVersion);
            if (isSnapshot && !detectedScalaVersion.endsWith("-SNAPSHOT")) {
                detectedScalaVersion = detectedScalaVersion.substring(0, detectedScalaVersion.lastIndexOf('-', detectedScalaVersion.lastIndexOf('-')-1)) + "-SNAPSHOT";
            }
            if (StringUtils.isNotEmpty(scalaVersion)) {
                if (!scalaVersion.equals(detectedScalaVersion)) {
                    getLog().warn("scala library version define in dependencies doesn't match the scalaVersion of the plugin");
                }
                //getLog().info("suggestion: remove the scalaVersion from pom.xml"); //scalaVersion could be define in a parent pom where lib is not required
            } else {
                scalaVersion = detectedScalaVersion;
            }
        }
        if (StringUtils.isEmpty(scalaVersion)) {
            throw new MojoFailureException("no scalaVersion detected or set");
        }
        if (checkMultipleScalaVersions) {
            checkCorrectVersionsOfScalaLibrary();
        }
    }
    /** this method checks to see if there are multiple versions of the scala library
     * @throws Exception */
    private void checkCorrectVersionsOfScalaLibrary() throws Exception {
        getLog().info("Checking for multiple versions of scala");
        //TODO - Make sure we handle bad artifacts....
        // TODO: note that filter does not get applied due to MNG-3236
            checkArtifactForScalaVersion(dependencyTreeBuilder.buildDependencyTree( project, localRepository, artifactFactory,
                    artifactMetadataSource, null, artifactCollector ));
    }


    /** Visits a node (and all dependencies) to see if it contains duplicate scala versions */
    private void checkArtifactForScalaVersion(DependencyNode rootNode) throws Exception {
        final CheckScalaVersionVisitor visitor = new CheckScalaVersionVisitor(scalaVersion, getLog());

        CollectingDependencyNodeVisitor collectingVisitor = new CollectingDependencyNodeVisitor();
        DependencyNodeVisitor firstPassVisitor = new FilteringDependencyNodeVisitor( collectingVisitor, createScalaDistroDependencyFilter() );
        rootNode.accept( firstPassVisitor );

        DependencyNodeFilter secondPassFilter = new AncestorOrSelfDependencyNodeFilter( collectingVisitor.getNodes() );
        DependencyNodeVisitor filteredVisitor = new FilteringDependencyNodeVisitor( visitor, secondPassFilter );

        rootNode.accept( filteredVisitor );

        if(visitor.isFailed()) {
            if(failOnMultipleScalaVersions) {
                getLog().error("Multiple versions of scala libraries detected!");
                throw new MojoFailureException("Multiple versions of scala libraries detected!");
            } else {
                getLog().warn("Multiple versions of scala libraries detected!");
            }
        }

    }
    /**
     * @return
     *          A filter to only extract artifacts deployed from scala distributions
     */
    private DependencyNodeFilter createScalaDistroDependencyFilter() {
        List<ArtifactFilter> filters = new ArrayList<ArtifactFilter>();
        filters.add(new ScalaDistroArtifactFilter());
        return new AndDependencyNodeFilter(filters);
    }



    protected abstract void doExecute() throws Exception;


    protected JavaMainCaller getScalaCommand() throws Exception {
        ScalaPluginInfo plugin_info = getCompilerPluginInfo();
        JavaMainCaller cmd = getEmptyScalaCommand(scalaClassName, plugin_info);
        cmd.addArgs(args);
        plugin_info.addToCall(cmd);
        cmd.addJvmArgs(jvmArgs);
        return cmd;
    }

    protected JavaMainCaller getEmptyScalaCommand(String mainClass)
            throws Exception
    {
        return getEmptyScalaCommand(mainClass, new ScalaPluginInfo());
    }

    protected JavaMainCaller getEmptyScalaCommand(String mainClass, ScalaPluginInfo plugin_info) throws Exception {
    	//TODO - Fork or not depending on configuration?
        JavaMainCaller cmd;
        String java_classpath = getToolClasspath(plugin_info);
        if(fork) {
           if( new VersionNumber(scalaVersion).compareTo(new VersionNumber("2.8.0")) >= 0) {
               //TODO - Version 2.8.0 and above support passing arguments in a file via the @ argument.
               getLog().info("use scala command with args in file");
               cmd = new ScalaCommandWIthArgsInFile(this, mainClass, java_classpath, null, null);
           } else {
               getLog().info("use java command with args in file forced : " + forceUseArgFile);
               cmd = new JavaCommand(this, mainClass, java_classpath, null, null, forceUseArgFile);
           }
        } else  {
            cmd = new ReflectionJavaMainCaller(this, mainClass, java_classpath, null, null);
        }
        cmd.addJvmArgs("-Xbootclasspath/a:"+ getBootClasspath());
        return cmd;
    }

    private String getToolClasspath(ScalaPluginInfo plugin_info) throws Exception {
        Set<String> classpath = new HashSet<String>();
        addToClasspath(SCALA_GROUPID, "scala-compiler", scalaVersion, classpath);
//        addToClasspath(SCALA_GROUPID, "scala-decoder", scalaVersion, classpath);
//        addToClasspath(SCALA_GROUPID, "scala-dbc", scalaVersion, classpath);
        if (dependencies != null) {
            for(BasicArtifact artifact: dependencies) {
                addToClasspath(artifact.groupId, artifact.artifactId, artifact.version, classpath);
            }
        }
        classpath.addAll(plugin_info.dependency_jars);
        return JavaCommand.toMultiPath(classpath.toArray(new String[classpath.size()]));
    }

    private String getBootClasspath() throws Exception {
        Set<String> classpath = new HashSet<String>();
        addToClasspath(SCALA_GROUPID, SCALA_LIBRARY_ARTIFACTID, scalaVersion, classpath);
        return JavaCommand.toMultiPath(classpath.toArray(new String[classpath.size()]));
    }

    /**
     * @return
     *           This returns whether or not the scala version can support having java sent into the compiler
     */
	protected boolean isJavaSupportedByCompiler() {
		return new VersionNumber(scalaVersion).compareTo(new VersionNumber("2.7.2")) >= 0;
	}

    /** Retrieves path information of scala compiler plugins. */
    private ScalaPluginInfo getCompilerPluginInfo() throws Exception {
        if (compilerPlugins != null) {
            Set<String> ignoreClasspath = new HashSet<String>();
            addToClasspath(SCALA_GROUPID, "scala-compiler", scalaVersion,
                    ignoreClasspath);
            addToClasspath(SCALA_GROUPID, SCALA_LIBRARY_ARTIFACTID,
                    scalaVersion, ignoreClasspath);
            Set<String> plugin_jars = new HashSet<String>();
            Set<String> dependency_jars = new HashSet<String>();
            for (BasicArtifact artifact : compilerPlugins) {
                // TODO - Ensure proper scala version for plugins
                addToClasspath(artifact.groupId, artifact.artifactId,
                        artifact.version, plugin_jars, dependency_jars);
            }
            return new ScalaPluginInfo(plugin_jars, dependency_jars, ignoreClasspath);
        } else {
            return new ScalaPluginInfo();
        }
    }
}
