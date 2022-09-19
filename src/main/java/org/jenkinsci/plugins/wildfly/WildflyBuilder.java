/**
 * The MIT License
 * 
 * Copyright (c) 2015, Dan B. Dillingham
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.jenkinsci.plugins.wildfly;

import static org.apache.commons.lang.StringUtils.trim;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import javax.servlet.ServletException;
import org.jboss.as.cli.scriptsupport.CLI;
import org.jboss.as.cli.scriptsupport.CLI.Result;
import org.jboss.dmr.ModelNode;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.TopLevelItem;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/**
 * @author Dan B. Dillingham
 *
 */
public class WildflyBuilder extends Builder {

    private final String war;
    private final String host;
    private final String port;
    private final String username;
    private final String password;
    private final String server;

    @DataBoundConstructor
    public WildflyBuilder(String war, String host, String port, String username, String password,
            String server) {
        this.war = trim(war);
        this.host = trim(host);
        this.port = trim(port);
        this.username = trim(username);
        this.password = trim(password);
        this.server = trim(server);
    }

    public String getWar() {
        return war;
    }

    public String getHost() {
        return host;
    }

    public String getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getServer() {
        return server;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {

        char[] passwordAsCharArray;
        CLI.Result result;
        String warPath, warFilename, response, localPath, remotePath;
        FilePath localFP = null;

        try {
            int portAsInt = Integer.parseInt(port);

            FilePath fp = new FilePath(build.getWorkspace(), war);
            remotePath = fp.getRemote();
            if (!fp.exists()) {
                listener.fatalError("The '" + war + "' file does not exist in workspace.");
                return false;
            }

            // if running on a remote slave, copy the WAR file to the master so CLI (which must run
            // on the master) can access it
            if (fp.isRemote()) {
                Jenkins jenkins = Jenkins.getInstanceOrNull();
                if (jenkins != null) {
                    localPath = jenkins
                            .getWorkspaceFor((TopLevelItem) build.getProject().getRootProject())
                            .getRemote();
                    localPath = localPath.concat("/" + war);
                    localFP = new FilePath(new File(localPath));
                    fp.act(new GrabWARFile(localFP));
                    warPath = localPath;
                } else {
                    listener.fatalError(
                            "Jenkins service has not been started, or was already shut down, or we are running on an unrelated JVM.");
                    return false;
                }

            } else
                warPath = remotePath;

            CLI cli = CLI.newInstance();
            if (username.length() > 0) {
                passwordAsCharArray = password.toCharArray();
                cli.connect(host, portAsInt, username, passwordAsCharArray);
            } else
                cli.connect(host, portAsInt, null, null);

            listener.getLogger().println("Connected to WildFly at " + host + ":" + port);

            int idx = war.lastIndexOf("/");
            if (idx > 0) {
                warFilename = war.substring(idx + 1, war.length());
            } else {
                warFilename = war;
            }

            // if application exists, undeploy it first...

            boolean localMode = checkInLocalMode(cli, remotePath, listener);

            if (localMode) {
                listener.getLogger().println(
                        "[WARNING] In local mode, there will be no server return message.");
                listener.getLogger().println("Try undeploying Application " + warFilename + " ...");

                try {
                    if (server.length() > 0)
                        result = cli.cmd("undeploy " + warFilename + " --server-groups=" + server);
                    else
                        result = cli.cmd("undeploy " + warFilename);
                    if (!result.isSuccess()) {
                        listener.fatalError("Undeploy failure.");
                        return false;
                    } else
                        listener.getLogger().println("Successful undeploy.");
                } catch (IllegalArgumentException e) {
                    Throwable cause = e.getCause();
                    String message = cause.toString();
                    if (message.indexOf("WFLYCTL0216") < 0 || message.indexOf(warFilename) < 0) {
                        listener.fatalError("Undeploy failure.");
                        return false;
                    }
                }


                listener.getLogger().println("Deploying " + warFilename + " ...");
                if (server.length() > 0)
                    result = cli.cmd("deploy " + warPath + " --server-groups=" + server);
                else
                    result = cli.cmd("deploy " + warPath);


                if (!result.isSuccess()) {
                    listener.fatalError("Deployment failure.");
                    return false;
                } else
                    listener.getLogger().println("Successful deployment.");

            } else {
                if (applicationExists(cli, warFilename, server, listener)) {
                    listener.getLogger()
                            .println("Application " + warFilename + " exists, undeploying...");
                    if (server.length() > 0)
                        result = cli.cmd("undeploy " + warFilename + " --server-groups=" + server);
                    else
                        result = cli.cmd("undeploy " + warFilename);
                    response = getWildFlyResponse(result);
                    if (response.indexOf("{\"outcome\" => \"failed\"") >= 0) {
                        listener.fatalError(response);
                        return false;
                    } else
                        listener.getLogger().println(response);
                }

                listener.getLogger().println("Deploying " + warFilename + " ...");
                if (server.length() > 0)
                    result = cli.cmd("deploy " + warPath + " --server-groups=" + server);
                else
                    result = cli.cmd("deploy " + warPath);

                response = getWildFlyResponse(result);
                if (response.indexOf("{\"outcome\" => \"failed\"") >= 0) {
                    listener.fatalError(response);
                    return false;
                } else
                    listener.getLogger().println(response);
            }



            cli.disconnect();

            if (localFP != null)
                localFP.delete();

        } catch (Exception e) {
            e.printStackTrace();
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            listener.fatalError(e.getMessage());
            listener.fatalError(sw.toString());
            return false;
        }

        return true;

    }

    private boolean checkInLocalMode(CLI cli, String server, BuildListener listener) {

        CLI.Result result;

        if (server.length() > 0) {
            result = cli.cmd("deployment-info --server-group=" + server);
        } else {
            result = cli.cmd("deployment-info");
        }
        return result.isLocalCommand();
    }

    private boolean applicationExists(CLI cli, String war, String server, BuildListener listener) {

        CLI.Result result;
        String response;

        if (server.length() > 0) {
            result = cli.cmd("deployment-info --server-group=" + server);
        } else {
            result = cli.cmd("deployment-info");
        }
        listener.getLogger().println("result success = " + result.isSuccess());
        listener.getLogger().println("result local = " + result.isLocalCommand());
        response = getWildFlyResponse(result);

        if (response.indexOf(war) < 0)
            return false;
        else
            return true;
    }

    private String getWildFlyResponse(Result result) {

        ModelNode modelNode = result.getResponse();
        String response = modelNode.asString();

        return response;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private static final class GrabWARFile implements FileCallable<Void> {
        private static final long serialVersionUID = 1;

        private final FilePath fp;

        public GrabWARFile(FilePath fp) {
            this.fp = fp;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) {

            try {
                FilePath remoteFilePath = new FilePath(f);
                fp.copyFrom(remoteFilePath);
            } catch (Exception e) {
                return null;
            }

            return null;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {

        }

    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckWar(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please specify a WAR or EAR filename.");
            if (!value.toLowerCase().contains(".war".toLowerCase())
                    && !value.toLowerCase().contains(".ear".toLowerCase()))
                return FormValidation.error("Please specify a valid WAR or EAR filename.");
            if (value.length() < 7)
                return FormValidation.warning("Is that a valid WAR or EAR filename?");
            return FormValidation.ok();
        }

        public FormValidation doCheckHost(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please specify a valid hostname.");
            if (value.length() < 4)
                return FormValidation.warning("Is that a valid hostname?");
            return FormValidation.ok();
        }

        public FormValidation doCheckPort(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0 || value.length() < 4 || value.length() > 5
                    || !value.matches("[0-9]+"))
                return FormValidation.error("Please specify a valid port number.");
            return FormValidation.ok();
        }

        public FormValidation doCheckPassword(@QueryParameter String value,
                @QueryParameter String username) throws IOException, ServletException {
            if (value.length() != 0)
                if (username.length() == 0)
                    return FormValidation
                            .error("Both a user name and a password must be specified.");
            return FormValidation.ok();
        }

        public FormValidation doCheckUsername(@QueryParameter String value,
                @QueryParameter String password) throws IOException, ServletException {
            if (value.length() != 0)
                if (password.length() == 0)
                    return FormValidation
                            .error("Both a user name and a password must be specified.");
            return FormValidation.ok();
        }

        public FormValidation doCheckServer(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() > 0 && value.length() < 5)
                return FormValidation
                        .warning("Is that a valid server group or standalone server name?");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Deploy WAR/EAR to WildFly";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req, formData);
        }

    }

}

