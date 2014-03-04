package org.oscim.ios.backend;

import org.oscim.backend.canvas.Bitmap;
import org.oscim.backend.canvas.Paint;

public class IosPaint implements Paint {

	@Override
	public int getColor() {
		return 0;
	}

	@Override
	public int getTextHeight(String text) {
		return 0;
	}

	@Override
	public int getTextWidth(String text) {
		return 0;
	}

	@Override
	public void setBitmapShader(Bitmap bitmap) {
	}

	@Override
	public void setColor(int color) {
	}

	@Override
	public void setDashPathEffect(float[] strokeDasharray) {
	}

	@Override
	public void setStrokeCap(Cap cap) {
	}

	@Override
	public void setStrokeWidth(float width) {
	}

	@Override
	public void setStyle(Style style) {
	}

	@Override
	public void setTextAlign(Align align) {
	}

	@Override
	public void setTextSize(float textSize) {
	}

	@Override
	public void setTypeface(FontFamily fontFamily, FontStyle fontStyle) {
	}

	@Override
	public float measureText(String text) {
		return IosCanvas.font.getBounds(text).width;
	}

	@Override
	public float getFontHeight() {
		return IosCanvas.font.getLineHeight();
	}

	@Override
	public float getFontDescent() {
		return IosCanvas.font.getDescent();
	}

}
