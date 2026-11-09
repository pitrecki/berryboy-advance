package javax.microedition.lcdui;
/** Host LCDUI double; not a BlackBerry runtime implementation. */
public final class Form extends Displayable {
    private final java.util.List<Object> items=new java.util.ArrayList<>();
    public Form(String title){super(title);}
    public int append(Object item){items.add(item);return items.size()-1;}
    public int size(){return items.size();} public Object get(int index){return items.get(index);}
}
