package tricatch.oe.proxy.cfg;

import tricatch.oe.proxy.cfg.attr.*;

public class Config {

    private Https https = new Https();
    private Console console = new Console();
    private Ca ca = new Ca();
    private String serverName = "Gotpache Console";

    public Https getHttps() {
        return https;
    }

    public void setHttps(Https https) {
        this.https = https;
    }

    public Console getConsole(){
        return this.console;
    }

    public void setConsole(Console console){
        this.console = console;
    }

    public Ca getCa() {
        return ca;
    }

    public void setCa(Ca ca) {
        this.ca = ca;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }
}
