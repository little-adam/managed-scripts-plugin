package org.jenkinsci.plugins.managedscripts;

import hudson.model.*;
import hudson.model.Queue;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.managedscripts.PowerShellConfig.Arg;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import hudson.FilePath;
import hudson.tasks.CommandInterpreter;

/**
 * A project that uses this builder can choose a build step from a list of predefined powershell files that are used as command line scripts.
 * <p>
 *
 * @author Arnaud Tamaillon (Greybird)
 * @see hudson.tasks.BatchFile
 */
public class PowerShellBuildStep extends CommandInterpreter {

    private static final Logger LOGGER = Logger.getLogger(PowerShellBuildStep.class.getName());

    private final String[] buildStepArgs;

    public static class ArgValue implements Serializable {
        public final String arg;

        @DataBoundConstructor
        public ArgValue(String arg) {
            this.arg = arg;
        }
    }

    public static class ScriptBuildStepArgs {
        public final boolean defineArgs;
        public final ArgValue[] buildStepArgs;

        @DataBoundConstructor
        public ScriptBuildStepArgs(boolean defineArgs, ArgValue[] buildStepArgs) {
            this.defineArgs = defineArgs;
            this.buildStepArgs = buildStepArgs == null ? new ArgValue[0] : Arrays.copyOf(buildStepArgs, buildStepArgs.length);
        }
    }

    /**
     * The constructor used at form submission
     *
     * @param buildStepId         the Id of the config file
     * @param scriptBuildStepArgs whether to save the args and arg values (the boolean is required because of html form submission, which also sends hidden values)
     */
    @DataBoundConstructor
    public PowerShellBuildStep(String buildStepId, ScriptBuildStepArgs scriptBuildStepArgs) {
        super(buildStepId);
        List<String> l = null;
        if (scriptBuildStepArgs != null && scriptBuildStepArgs.defineArgs
                && scriptBuildStepArgs.buildStepArgs != null) {
            l = new ArrayList<String>();
            for (ArgValue arg : scriptBuildStepArgs.buildStepArgs) {
                l.add(arg.arg);
            }
        }
        this.buildStepArgs = l == null ? null : l.toArray(new String[l.size()]);
    }

    /**
     * The constructor
     *
     * @param buildStepId   the Id of the config file
     * @param buildStepArgs list of arguments specified as buildStepargs
     */
    public PowerShellBuildStep(String buildStepId, String[] buildStepArgs) {
        super(buildStepId); // save buildStepId as command
        this.buildStepArgs = buildStepArgs == null ? new String[0] : Arrays.copyOf(buildStepArgs, buildStepArgs.length);
    }

    public String getBuildStepId() {
        return getCommand();
    }

    public String[] getBuildStepArgs() {
        String[] args = buildStepArgs == null ? new String[0] : buildStepArgs;
        return Arrays.copyOf(args, args.length);
    }

    @Override	
	public String[] buildCommandLine(FilePath script) {
		List<String> cml = new ArrayList<String>();
           
        if (isRunningOnWindows(script)) {
			cml.add("powershell.exe");			
			cml.add("-ExecutionPolicy");
			cml.add("ByPass");
			cml.add("& \'" + script.getRemote() + "\'");
        } else {
			cml.add("/usr/local/bin/powershell");
			cml.add("-NonInteractive");
			cml.add("& \'" + script.getRemote() + "\'");
			
            // ExecutionPolicy option does not work (and is not required) for non-Windows platforms
            // See https://github.com/PowerShell/PowerShell/issues/2742
        }
		
		// Add additional parameters set by user
        if (buildStepArgs != null) {
            for (String arg : buildStepArgs) {
                cml.add(arg);
            }
        }
		
		return (String[]) cml.toArray(new String[cml.size()]);
    }
	
    private boolean isRunningOnWindows(FilePath script) {
        // Ideally this would use a property of the build/run, but unfortunately CommandInterpreter only gives us the
        // FilePath, so we need to guess based on that.

        // Running remotely, guess based on the path. A path starting with something like "C:\" is Windows.
        String path = script.getRemote();
        return path.length() > 3 && path.charAt(1) == ':' && path.charAt(2) == '\\';
    }

    @Override
    protected String getContents() {

        Executor executor = Executor.currentExecutor();
        if(executor!=null) {
            Queue.Executable currentExecutable = executor.getCurrentExecutable();
            if(currentExecutable != null) {
                Config buildStepConfig = ConfigFiles.getByIdOrNull((Run<?, ?>) currentExecutable, getBuildStepId());
                if (buildStepConfig == null) {
                    throw new IllegalStateException(Messages.config_does_not_exist(getBuildStepId()));
                }
                return buildStepConfig.content + "\r\nexit $LastExitCode";
            } else {
                String msg = "current executable not accessable! can't get content of script: "+getBuildStepId();
                LOGGER.log(Level.SEVERE, msg);
                throw new RuntimeException(msg);
            }
        } else {
            String msg = "current executor not accessable! can't get content of script: "+getBuildStepId();
            LOGGER.log(Level.SEVERE, msg);
            throw new RuntimeException(msg);
        }

    }

    @Override
    protected String getFileExtension() {
        return ".ps1";
    }

    //Overridden for better type safety.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link PowerShellBuildStep}.
     */
    @Extension(ordinal = 60)
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        final Logger logger = Logger.getLogger(PowerShellBuildStep.class.getName());

        /**
         * Enables this builder for all kinds of projects.
         */
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        @Override
        public String getDisplayName() {
            return Messages.powershell_buildstep_name();
        }

        /**
         * gets the argument description to be displayed on the screen when selecting a config in the dropdown
         *
         * @param configId the config id to get the arguments description for
         * @return the description
         */
        @JavaScriptMethod
        public String getArgsDescription(@AncestorInPath ItemGroup context, String configId) {
            final PowerShellConfig config = ConfigFiles.getByIdOrNull(context, configId);
            if (config != null) {
                if (config.args != null && !config.args.isEmpty()) {
                    StringBuilder sb = new StringBuilder("Required arguments: ");
                    int i = 1;
                    for (Iterator<Arg> iterator = config.args.iterator(); iterator.hasNext(); i++) {
                        Arg arg = iterator.next();
                        sb.append(i).append(". ").append(arg.name);
                        if (iterator.hasNext()) {
                            sb.append(" | ");
                        }
                    }
                    return sb.toString();
                } else {
                    return "No arguments required";
                }
            }
            return "please select a script!";
        }

        @JavaScriptMethod
        public List<Arg> getArgs(@AncestorInPath ItemGroup context, String configId) {
            final PowerShellConfig config = ConfigFiles.getByIdOrNull(context, configId);
            if (config != null) {
                return config.args;
            }
            return Collections.emptyList();
        }

        /**
         * validate that an existing config was chosen
         *
         * @param buildStepId the buildStepId
         * @return
         */
        public FormValidation doCheckBuildStepId(@AncestorInPath ItemGroup context, @QueryParameter String buildStepId) {
            final PowerShellConfig config = ConfigFiles.getByIdOrNull(context, buildStepId);
            if (config != null) {
                return FormValidation.ok();
            } else {
                return FormValidation.error("you must select a valid powershell file");
            }
        }

        /**
         * Return all batch files (templates) that the user can choose from when creating a build step. Ordered by name.
         *
         * @return A collection of batch files of type {@link WinBatchConfig}.
         */
        public ListBoxModel doFillBuildStepIdItems(@AncestorInPath ItemGroup context) {
            List<Config> configsInContext = ConfigFiles.getConfigsInContext(context, PowerShellConfig.PowerShellConfigProvider.class);
            Collections.sort(configsInContext, new Comparator<Config>() {
                public int compare(Config o1, Config o2) {
                    return o1.name.compareTo(o2.name);
                }
            });
            ListBoxModel items = new ListBoxModel();
            items.add("please select", "");
            for (Config config : configsInContext) {
                items.add(config.name, config.id);
            }
            return items;
        }

        private ConfigProvider getBuildStepConfigProvider() {
            ExtensionList<ConfigProvider> providers = ConfigProvider.all();
            return providers.get(PowerShellConfig.PowerShellConfigProvider.class);
        }
    }
}
