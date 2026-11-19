package javax.microedition.lcdui;
/** Host LCDUI double; not a BlackBerry runtime implementation. */
public final class ChoiceGroup implements Choice {
    private final int count;
    private int selected;
    public ChoiceGroup(String title,int kind,String[] labels,Image[] images){
        count=labels.length;selected=count==0?-1:0;
    }
    public int getSelectedIndex(){return selected;}
    public void setSelectedIndex(int index,boolean selected){
        if(index<0||index>=count)throw new IndexOutOfBoundsException();
        if(selected)this.selected=index;
    }
}
