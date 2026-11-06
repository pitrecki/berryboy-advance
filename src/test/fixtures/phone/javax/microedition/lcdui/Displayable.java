package javax.microedition.lcdui;
public class Displayable {
    private volatile String title;
    protected Displayable(String title){this.title=title;}
    public void setTitle(String value){title=value;}
    public String getTitle(){return title;}
    public void addCommand(Command command){}
    public void setCommandListener(CommandListener listener){}
}
