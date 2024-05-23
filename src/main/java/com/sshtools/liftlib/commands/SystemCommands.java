package com.sshtools.liftlib.commands;

import com.sshtools.liftlib.ElevatedClosure;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface SystemCommands {
    
    public enum ProcessRedirect {
        INHERIT, DISCARD, PIPE;
        
        public Redirect toRedirect() {
            switch(this) {
            case INHERIT:
                return Redirect.INHERIT;
            case DISCARD:
                return Redirect.DISCARD;
            case PIPE:
                return Redirect.PIPE;
            default:
                throw new UnsupportedOperationException();
            }
        }
    }

    public abstract class AbstractSystemCommands implements SystemCommands {
        private Map<String, String> env = new HashMap<>();
        private Optional<ProcessRedirect> stdin;
        private Optional<ProcessRedirect> stdout;
        private Optional<ProcessRedirect> stderr;
        private Optional<Path> dir;

        protected AbstractSystemCommands(Map<String, String> env, Optional<ProcessRedirect> stdin, Optional<ProcessRedirect> stdout,
                Optional<ProcessRedirect> stderr, Optional<Path> dir) {
            this.env.putAll(env);
            this.stdin = stdin;
            this.stdout = stdout;
            this.stderr = stderr;
            this.dir = dir;
        }

        @Override
        public SystemCommands dir(Path dir) {
            this.dir = Optional.ofNullable(dir);
            return this;
        }
        
        @Override
        public Optional<Path> dir() {
            return dir;
        }

        @Override
        public final Map<String, String> env() {
            return env;
        }

        @Override
        public final SystemCommands env(Map<String, String> env) {
            this.env.putAll(env);
            return this;
        }

        @Override
        public SystemCommands stderr(ProcessRedirect stderr) {
            this.stderr = Optional.of(stderr);
            return this;
        }

        @Override
        public SystemCommands stdout(ProcessRedirect stdout) {
            this.stdout = Optional.of(stdout);
            return this;
        }

        @Override
        public SystemCommands stdin(ProcessRedirect stdin) {
            this.stdin = Optional.of(stdin);
            return this;
        }

        @Override
        public Optional<ProcessRedirect> stderr() {
            return stderr;
        }

        @Override
        public Optional<ProcessRedirect> stdout() {
            return stdout;
        }

        @Override
        public Optional<ProcessRedirect> stdin() {
            return stdin;
        }

    }
    
    Optional<Path> dir();

    Map<String, String> env();

    void onLog(Consumer<String[]> onLog);

    PrintWriter pipe(Consumer<String> input, String... args) throws IOException;

    SystemCommands privileged();

    SystemCommands dir(Path dir);

    default SystemCommands dir(File dir) {
        return dir(dir == null ? null : dir.toPath());
    }

    SystemCommands logged();

    SystemCommands env(Map<String, String> env);

    SystemCommands stderr(ProcessRedirect redirect);

    SystemCommands stdout(ProcessRedirect redirect);

    SystemCommands stdin(ProcessRedirect redirect);

    Optional<ProcessRedirect> stderr();

    Optional<ProcessRedirect> stdout();

    Optional<ProcessRedirect> stdin();

    void run(String... args) throws IOException;

    Collection<String> output(String... args) throws IOException;

    Collection<String> silentOutput(String... args);

    int result(String... args) throws IOException;

    Collection<String> pipeTo(String content, String... args) throws IOException;

    default int consume(Consumer<String> consumer, String... args) throws IOException {
        return consume(consumer, null, args);
    }

    int consume(Consumer<String> consumer, Consumer<String> errConsumer, String... args) throws IOException;

    <R extends Serializable> R task(ElevatedClosure<R, Serializable> task) throws Exception;

}
