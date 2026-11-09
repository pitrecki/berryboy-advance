package javax.microedition.lcdui;
public class Displayable {
    private volatile String title;
    private CommandListener listener;
    private final java.util.List<Command> commands=new java.util.ArrayList<>();
    protected Displayable(String title){this.title=title;}
    public void setTitle(String value){title=value;}
    public String getTitle(){return title;}
    public void addCommand(Command command){commands.add(command);}
    public void setCommandListener(CommandListener listener){this.listener=listener;}
    public void fire(Command command){listener.commandAction(command,this);}
    public void fire(String label){fire(commands.stream().filter(c->c.getLabel().equals(label)).findFirst().orElseThrow());}
    public java.util.List<Command> commands(){return java.util.List.copyOf(commands);}
}
