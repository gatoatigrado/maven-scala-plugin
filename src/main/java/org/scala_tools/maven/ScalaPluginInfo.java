package org.scala_tools.maven;

import java.util.HashSet;
import java.util.Set;

import org.scala_tools.maven.executions.JavaMainCaller;

/**
 * Simple wrapper class for returning plugins and their dependencies.
 * @author gatoatigrado (nicholas tung) [email: ntung at ntung]
 * @license This file is licensed under BSD license, available at
 *          http://creativecommons.org/licenses/BSD/. While not required, if you
 *          make changes, please consider contributing back!
 */
public class ScalaPluginInfo {
    public Set<String> plugin_jars;
    public Set<String> dependency_jars;

    public ScalaPluginInfo(Set<String> plugin_jars,
            Set<String> dependency_jars, Set<String> ignoreClasspath)
    {
        plugin_jars.removeAll(ignoreClasspath);
        dependency_jars.removeAll(ignoreClasspath);
        this.plugin_jars = plugin_jars;
        this.dependency_jars = dependency_jars;
    }

    public ScalaPluginInfo() {
        plugin_jars = new HashSet<String>();
        dependency_jars = new HashSet<String>();
    }

    /** Adds appropriate compiler plugins to the scalac command. */
    public void addToCall(JavaMainCaller scalac) throws Exception {
        for (String plugin : plugin_jars) {
            scalac.addArgs("-Xplugin:" + plugin);
        }
    }
}
