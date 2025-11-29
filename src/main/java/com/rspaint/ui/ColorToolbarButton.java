package com.rspaint.ui;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import javax.swing.*;
import java.awt.*;

public class ColorToolbarButton extends ToolbarButton {
    @Setter @Getter
    private Color color;

    @Setter @Getter
    private boolean selected;


    public ColorToolbarButton(int index, Color color) {
        super(index);
        if (color == null) {
            this.color = Color.LIGHT_GRAY;
        }
        else {
            this.color = color;
        }
    }

    @Override public void draw(Graphics2D g, Client ctx) {
        if (this.selected){
            this.bounds.grow(2, 2);
        }
        if (this.color != null) {
            g.setColor(this.color);
            g.fill(this.bounds);
        }
        if (mouseOver(ctx) || isSelected()){
            g.setColor(Color.WHITE);
        }
        else{
            g.setColor(Color.GRAY);
        }
        g.draw(this.bounds);
        if (this.selected){
            this.bounds.grow(-2, -2);
        }
    }

    // Swaps colors with another ColorToolbarButton
    public void onDrag(ColorToolbarButton b) {
        Color tmp = this.color;
        this.color = b.getColor();
        b.setColor(tmp);
    }
}
