package javax.microedition.lcdui;
/** Host LCDUI double; not a BlackBerry runtime implementation. */
public final class TextField {
    public static final int ANY=0;
    private final String label; private String text;
    public TextField(String label,String text,int maximum,int constraints){this.label=label;this.text=text;}
    public String getString(){return text;} public void setString(String text){this.text=text;} public String getLabel(){return label;}
}
