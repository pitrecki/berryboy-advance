package javax.microedition.lcdui;
public final class Command {
    public static final int SCREEN=1,BACK=2,OK=4,EXIT=7,ITEM=8;
    private final String label;
    public Command(String label,int type,int priority){this.label=label;}
    public String getLabel(){return label;}
}
