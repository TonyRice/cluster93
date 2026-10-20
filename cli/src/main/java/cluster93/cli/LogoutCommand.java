package cluster93.cli;

import picocli.CommandLine.Command;

@Command(name = "logout", description = "Forget the saved login.")
final class LogoutCommand extends Base {

    @Override
    int execute() {
        out().println(Config.forget(io(), profile()) ? "Logged out." : "There was no saved login.");
        return 0;
    }
}
