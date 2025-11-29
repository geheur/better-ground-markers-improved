package com.rspaint.ui;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import javax.swing.*;
import java.awt.*;

// Toolbar specifically for the "Add New Color" function
public class NewColorToolbarButton extends ToolbarButton{
    // Field to queue up a new color to add
    @Setter @Getter
    private Color newColor;

    public NewColorToolbarButton(int index) {
        super(index);
    }

    // Draw normal square, but include a "+"
    @Override public void draw(Graphics2D g, Client ctx) {
        super.draw(g, ctx);
        g.setColor(Color.LIGHT_GRAY);
        g.drawLine(this.bounds.x + this.bounds.width / 2, this.bounds.y + 3, this.bounds.x + this.bounds.width / 2, this.bounds.y + this.bounds.height - 3);
        g.drawLine(this.bounds.x + 3, this.bounds.y + this.bounds.height / 2, this.bounds.x + this.bounds.width - 3, this.bounds.y + this.bounds.height / 2);
    }
}
