package org.osm2world.viewer.control.actions;

import java.awt.event.ActionEvent;
import java.io.Serial;
import java.util.Objects;

import javax.swing.*;

import org.osm2world.viewer.model.Data;
import org.osm2world.viewer.model.RenderOptions;
import org.osm2world.viewer.view.ViewerFrame;

public class SetMaxLevelAction extends AbstractAction {

	@Serial
	private static final long serialVersionUID = 1L;

	int level;
	ViewerFrame viewerFrame;
	Data data;
	RenderOptions renderOptions;

	public SetMaxLevelAction(int level, ViewerFrame viewerFrame, Data data, RenderOptions renderOptions) {

		super(level < Integer.MAX_VALUE ? Integer.toString(level) : "∞");

		putValue(SELECTED_KEY, Objects.equals(level, renderOptions.getMaxLevel()));

		this.level = level;
		this.viewerFrame = viewerFrame;
		this.data = data;
		this.renderOptions = renderOptions;

	}

	@Override
	public void actionPerformed(ActionEvent e) {

		renderOptions.setMaxLevel(level);

		putValue(SELECTED_KEY, Objects.equals(level, renderOptions.getMaxLevel()));

	}

}
