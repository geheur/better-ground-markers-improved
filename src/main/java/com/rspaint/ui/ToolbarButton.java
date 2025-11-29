package com.rspaint.ui;

import lombok.Getter;
import net.runelite.api.Client;
import java.awt.*;

public class ToolbarButton {
    // Button geometry
    Rectangle bounds;

    @Getter
    int index;

    public ToolbarButton(int index) {
        this.index = index;
        this.bounds = new Rectangle(2, 40 + 20 * (this.index), 16, 16);
    }

    // Sets index and adjusts the bounds accordingly
    public void setIndex(int index) {
        this.index = index;
        this.bounds.y = 40 + 20 * (this.index);
    }

    // Draw the button
    public void draw(Graphics2D g, Client ctx) {
        if (mouseOver(ctx)) {
            g.setColor(Color.WHITE);
            Rectangle r2 = new Rectangle(this.bounds);
            r2.grow(1, 1);
        } else {
            g.setColor(Color.LIGHT_GRAY);
        }
        g.draw(this.bounds);
    }

    // Returns true if the mouse intersects with this button
    public boolean mouseOver(Client ctx){
        int mx = ctx.getMouseCanvasPosition().getX();
        int my = ctx.getMouseCanvasPosition().getY();
        return this.bounds.contains(mx, my);
    }
}
