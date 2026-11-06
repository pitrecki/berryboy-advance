package javax.microedition.lcdui;
public final class List extends Displayable {
    public static final int IMPLICIT=3;
    public static final Command SELECT_COMMAND=new Command("Select",Command.OK,1);
    private final java.util.ArrayList<String> items=new java.util.ArrayList<>();
    private int selected;
    public List(String title,int type){super(title);}
    public synchronized int append(String value,Object image){items.add(value);return items.size()-1;}
    public synchronized void deleteAll(){items.clear();selected=0;}
    public synchronized int size(){return items.size();}
    public synchronized String getString(int index){return items.get(index);}
    public synchronized int getSelectedIndex(){return items.isEmpty()?-1:selected;}
    public synchronized void setSelectedIndex(int index,boolean value){selected=index;}
}
