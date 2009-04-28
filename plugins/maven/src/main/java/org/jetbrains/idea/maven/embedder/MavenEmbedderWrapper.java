package org.jetbrains.idea.maven.embedder;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import org.apache.maven.MavenTools;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.embedder.*;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.extension.ExtensionManager;
import org.apache.maven.model.Model;
import org.apache.maven.monitor.event.DefaultEventMonitor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.ComponentDescriptor;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.jetbrains.idea.maven.project.*;
import org.jetbrains.idea.maven.utils.MavenId;
import org.jetbrains.idea.maven.utils.MavenLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MavenEmbedderWrapper {
  private final MavenEmbedder myEmbedder;

  public MavenEmbedderWrapper(MavenEmbedder embedder) {
    myEmbedder = embedder;
  }

  public Model readModel(final String path, MavenProcess p) throws MavenProcessCanceledException {
    return doExecute(new Executor<Model>() {
      public Model execute() throws Exception {
        return myEmbedder.readModel(new File(path));
      }
    }, p);
  }

  public Pair<MavenExecutionResult, Set<MavenId>> readProject(MavenExecutionRequest request,
                                                              MavenProcess p) throws MavenProcessCanceledException {
    return doExecute(request, new RequestExecutor() {
      public MavenExecutionResult execute(MavenExecutionRequest request) {
        return myEmbedder.readProjectWithDependencies(request);
      }
    }, p);
  }

  private Set<MavenId> retrieveUnresolvedArtifactIds() {
    return this.<CustomWagonManager>getComponent(WagonManager.class).retrieveUnresolvedIds();
  }

  public void resolve(final Artifact artifact, final List<MavenRemoteRepository> remoteRepositories, MavenProcess process)
    throws MavenProcessCanceledException {
    doExecute(new Executor<Object>() {
      public Object execute() throws Exception {
        try {
          myEmbedder.resolve(artifact, convertRepositories(remoteRepositories), myEmbedder.getLocalRepository());
        }
        catch (Exception e) {
          MavenLog.LOG.info(e);
        }
        return null;
      }
    }, process);
  }

  private List<ArtifactRepository> convertRepositories(List<MavenRemoteRepository> repositories) {
    MavenTools tools = getComponent(MavenTools.class);

    List<ArtifactRepository> result = new ArrayList<ArtifactRepository>();
    for (MavenRemoteRepository each : repositories) {
      try {
        result.add(tools.buildArtifactRepository(each.toRepository()));
      }
      catch (InvalidRepositoryException e) {
        MavenLog.LOG.warn(e);
      }
    }

    return result;
  }

  public boolean resolvePlugin(final MavenPlugin plugin, final MavenProject nativeMavenProject, MavenProcess process)
    throws MavenProcessCanceledException {
    return doExecute(new Executor<Boolean>() {
      public Boolean execute() throws Exception {
        try {
          JBMavenEmbedderHelper.verifyPlugin(plugin, nativeMavenProject, myEmbedder);
        }
        catch (Exception e) {
          MavenLog.LOG.info(e);
          return false;
        }
        return true;
      }
    }, process).booleanValue();
  }

  public Artifact createArtifact(String groupId, String artifactId, String version, String type, String classifier) {
    return myEmbedder.createArtifactWithClassifier(groupId,
                                                   artifactId,
                                                   version,
                                                   type,
                                                   classifier);
  }

  public Artifact createProjectArtifact(String groupId, String artifactId, String version) {
    ArtifactFactory factory = getComponent(ArtifactFactory.class);
    return factory.createProjectArtifact(groupId, artifactId, version);
  }

  public <T> T getComponent(Class<? super T> clazz) {
    try {
      return (T)myEmbedder.getPlexusContainer().lookup(clazz.getName());
    }
    catch (ComponentLookupException e) {
      throw new RuntimeException(e);
    }
  }

  public Pair<MavenExecutionResult, Set<MavenId>> execute(MavenExecutionRequest request, MavenProcess p)
    throws MavenProcessCanceledException {
    request.addEventMonitor(new DefaultEventMonitor(new PlexusLoggerAdapter(myEmbedder.getLogger())));
    return doExecute(request, new RequestExecutor() {
      public MavenExecutionResult execute(MavenExecutionRequest request) {
        return myEmbedder.execute(request);
      }
    }, p);
  }

  public String getLocalRepository() {
    return myEmbedder.getLocalRepository().getBasedir();
  }

  public MavenEmbedder getEmbedder() {
    return myEmbedder;
  }

  public void release() {
    try {
      myEmbedder.stop();
    }
    catch (MavenEmbedderException e) {
      MavenLog.LOG.info(e);
    }
  }

  private interface Executor<T> {
    T execute() throws Exception;
  }

  private interface RequestExecutor {
    MavenExecutionResult execute(MavenExecutionRequest request) throws Exception;
  }

  private Pair<MavenExecutionResult, Set<MavenId>> doExecute(final MavenExecutionRequest request,
                                                             final RequestExecutor executor,
                                                             final MavenProcess p) throws MavenProcessCanceledException {
    return doExecute(new Executor<Pair<MavenExecutionResult, Set<MavenId>>>() {
      public Pair<MavenExecutionResult, Set<MavenId>> execute() throws Exception {
        request.setTransferListener(new TransferListenerAdapter(p.getIndicator()));
        MavenExecutionResult executionResult = executor.execute(request);
        return Pair.create(executionResult, retrieveUnresolvedArtifactIds());
      }
    }, p);
  }

  private <T> T doExecute(final Executor<T> executor, MavenProcess p) throws MavenProcessCanceledException {
    final Ref<T> result = new Ref<T>();
    final boolean[] cancelled = new boolean[1];
    final Throwable[] exception = new Throwable[1];

    Future<?> future = ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
      public void run() {
        try {
          result.set(executor.execute());
        }
        catch (ProcessCanceledException e) {
          cancelled[0] = true;
        }
        catch (Throwable e) {
          exception[0] = e;
        }
      }
    });

    while (true) {
      p.checkCanceled();
      try {
        future.get(50, TimeUnit.MILLISECONDS);
      }
      catch (TimeoutException ignore) {
      }
      catch (ExecutionException e) {
        throw new RuntimeException(e.getCause());
      }
      catch (InterruptedException e) {
        throw new MavenProcessCanceledException();
      }

      if (future.isDone()) break;
    }

    if (cancelled[0]) throw new MavenProcessCanceledException();
    if (exception[0] != null) throw new RuntimeException(exception[0]);

    return result.get();
  }

  public void customizeForResolve(MavenConsole console,
                                  MavenProcess process) {
    doCustomize(null, true, false, console, process);
  }

  public void customizeForResolve(boolean quickResolve,
                                  MavenProjectsTree tree,
                                  MavenConsole console,
                                  MavenProcess process) {
    doCustomize(tree, !quickResolve, false, console, process);
  }

  public void customizeForStrictResolve(MavenProjectsTree tree,
                                        MavenConsole console,
                                        MavenProcess process) {
    doCustomize(tree, true, true, console, process);
  }

  private void doCustomize(MavenProjectsTree tree,
                           boolean useRemoteRepository,
                           boolean failOnUnresolved,
                           MavenConsole console,
                           MavenProcess process) {
    this.<CustomArtifactFactory>getComponent(ArtifactFactory.class).customize();
    if (tree != null) {
      this.<CustomArtifactResolver>getComponent(ArtifactResolver.class).customize(tree);
    }
    this.<CustomWagonManager>getComponent(WagonManager.class).customize(useRemoteRepository, failOnUnresolved);
    this.<CustomExtensionManager>getComponent(ExtensionManager.class).customize();

    setConsoleAndLogger(console, process);
  }

  private void setConsoleAndLogger(MavenConsole console, MavenProcess process) {
    ((MavenConsoleLogger)myEmbedder.getLogger()).setConsole(console);

    myEmbedder.getDefaultRequest().setTransferListener(
      process == null ? null : new TransferListenerAdapter(process.getIndicator()));

    WagonManager wagon = getComponent(WagonManager.class);
    wagon.setDownloadMonitor(process == null ? null : new TransferListenerAdapter(process.getIndicator()));
  }

  public void reset() {
    setConsoleAndLogger(null, null);

    this.<CustomArtifactFactory>getComponent(ArtifactFactory.class).reset();
    this.<CustomArtifactResolver>getComponent(ArtifactResolver.class).reset();
    this.<CustomWagonManager>getComponent(WagonManager.class).reset();
    this.<CustomExtensionManager>getComponent(ExtensionManager.class).reset();
  }

  public static ContainerCustomizer createCustomizer() {
    return new ContainerCustomizer() {
      public void customize(PlexusContainer c) {
        ComponentDescriptor d;

        d = c.getComponentDescriptor(MavenTools.ROLE);
        d.setImplementation(CustomMavenTools.class.getName());

        d = c.getComponentDescriptor(ArtifactFactory.ROLE);
        d.setImplementation(CustomArtifactFactory.class.getName());

        d = c.getComponentDescriptor(ArtifactResolver.ROLE);
        d.setImplementation(CustomArtifactResolver.class.getName());

        d = c.getComponentDescriptor(WagonManager.ROLE);
        d.setImplementation(CustomWagonManager.class.getName());

        d = c.getComponentDescriptor(ExtensionManager.class.getName());
        d.setImplementation(CustomExtensionManager.class.getName());
      }
    };
  }
}
