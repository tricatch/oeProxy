package tricatch.oe.proxy.pass;

public interface Stopable extends Runnable {

    public void stop();

    public String getName();
}
