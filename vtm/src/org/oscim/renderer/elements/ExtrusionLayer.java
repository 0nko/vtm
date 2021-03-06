/*
 * Copyright 2012, 2013 Hannes Janetzek
 *
 * This file is part of the OpenScienceMap project (http://www.opensciencemap.org).
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.renderer.elements;

import java.nio.ShortBuffer;

import org.oscim.backend.canvas.Color;
import org.oscim.core.GeometryBuffer;
import org.oscim.core.GeometryBuffer.GeometryType;
import org.oscim.core.MapElement;
import org.oscim.core.Tile;
import org.oscim.renderer.MapRenderer;
import org.oscim.utils.FastMath;
import org.oscim.utils.KeyMap;
import org.oscim.utils.KeyMap.HashItem;
import org.oscim.utils.Tessellator;
import org.oscim.utils.geom.LineClipper;
import org.oscim.utils.pool.Inlist;
import org.oscim.utils.pool.SyncPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtrusionLayer extends RenderElement {
	static final Logger log = LoggerFactory.getLogger(ExtrusionLayer.class);

	private static final float S = MapRenderer.COORD_SCALE;
	private VertexItem mVertices;
	private VertexItem mCurVertices;
	private VertexItem mIndices[];
	private final VertexItem mCurIndices[];
	private LineClipper mClipper;

	/** 16 floats rgba for top, even-side, odd-sides and outline */
	public final float[] colors;
	public final int color;

	/** indices for: 0. even sides, 1. odd sides, 2. roof, 3. roof outline */
	public int numIndices[] = { 0, 0, 0, 0, 0 };
	public int sumVertices = 0;
	public int sumIndices = 0;

	//private final static int IND_EVEN_SIDE = 0;
	//private final static int IND_ODD_SIDE = 1;
	private final static int IND_ROOF = 2;

	// FIXME flip OUTLINE / MESH!
	private final static int IND_OUTLINE = 3;
	private final static int IND_MESH = 4;

	private final float mGroundResolution;

	private KeyMap<Vertex> mVertexMap;

	public int indexOffset;

	//private static final int NORMAL_DIR_MASK = 0xFFFFFFFE;
	//private int numIndexHits = 0;

	/**
	 * ExtrusionLayer for polygon geometries.
	 */
	public ExtrusionLayer(int level, float groundResolution, float[] colors) {
		super(RenderElement.EXTRUSION);
		this.level = level;
		this.colors = colors;
		this.color = 0;

		mGroundResolution = groundResolution;
		mVertices = mCurVertices = VertexItem.pool.get();

		mIndices = new VertexItem[5];
		mCurIndices = new VertexItem[5];
		for (int i = 0; i <= IND_MESH; i++)
			mIndices[i] = mCurIndices[i] = VertexItem.pool.get();

		mClipper = new LineClipper(0, 0, Tile.SIZE, Tile.SIZE);
	}

	/**
	 * ExtrusionLayer for triangle geometries.
	 */
	public ExtrusionLayer(int level, float groundResolution, int color) {
		super(RenderElement.EXTRUSION);
		this.level = level;
		this.color = color;
		this.colors = new float[4];
		float a = Color.aToFloat(color);
		colors[0] = a * Color.rToFloat(color);
		colors[1] = a * Color.gToFloat(color);
		colors[2] = a * Color.bToFloat(color);
		colors[3] = a;

		mGroundResolution = groundResolution;
		mVertices = mCurVertices = VertexItem.pool.get();

		mIndices = new VertexItem[5];
		mCurIndices = new VertexItem[5];

		mIndices[4] = mCurIndices[4] = VertexItem.pool.get();
		mVertexMap = vertexMapPool.get();
	}

	static SyncPool<Vertex> vertexPool = new SyncPool<Vertex>(8192, false) {
		@Override
		protected Vertex createItem() {
			return new Vertex();
		}
	};

	static SyncPool<KeyMap<Vertex>> vertexMapPool = new SyncPool<KeyMap<Vertex>>(64, false) {
		@Override
		protected KeyMap<Vertex> createItem() {
			return new KeyMap<Vertex>(2048);
		}
	};

	static class Vertex extends HashItem {
		short x, y, z, n;
		int id;

		@Override
		public boolean equals(Object obj) {
			Vertex o = (Vertex) obj;
			return x == o.x && y == o.y && z == o.z && n == o.n;
		}

		@Override
		public int hashCode() {
			return 7 + ((x << 16 | y) ^ (n << 16 | z)) * 31;
		}

		public Vertex set(short x, short y, short z, short n) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.n = n;
			return this;
		}
	}

	public void add(MapElement element) {
		if (element.type != GeometryType.TRIS)
			return;

		short[] index = element.index;
		float[] points = element.points;

		int vertexCnt = sumVertices;

		Vertex key = vertexPool.get();
		double scale = S * Tile.SIZE / 4096;

		for (int k = 0, n = index.length; k < n;) {
			if (index[k] < 0)
				break;

			/* FIXME: workaround: dont overflow max index id. */
			if (vertexCnt >= 1 << 16)
				break;

			int vtx1 = index[k++] * 3;
			int vtx2 = index[k++] * 3;
			int vtx3 = index[k++] * 3;

			float vx1 = points[vtx1 + 0];
			float vy1 = points[vtx1 + 1];
			float vz1 = points[vtx1 + 2];

			float vx2 = points[vtx2 + 0];
			float vy2 = points[vtx2 + 1];
			float vz2 = points[vtx2 + 2];

			float vx3 = points[vtx3 + 0];
			float vy3 = points[vtx3 + 1];
			float vz3 = points[vtx3 + 2];

			float ax = vx2 - vx1;
			float ay = vy2 - vy1;
			float az = vz2 - vz1;

			float bx = vx3 - vx1;
			float by = vy3 - vy1;
			float bz = vz3 - vz1;

			float cx = ay * bz - az * by;
			float cy = az * bx - ax * bz;
			float cz = ax * by - ay * bx;

			double len = Math.sqrt(cx * cx + cy * cy + cz * cz);

			// packing the normal in two bytes
			//	int mx = FastMath.clamp(127 + (int) ((cx / len) * 128), 0, 0xff);
			//	int my = FastMath.clamp(127 + (int) ((cy / len) * 128), 0, 0xff);
			//	short normal = (short) ((my << 8) | (mx & NORMAL_DIR_MASK) | (cz > 0 ? 1 : 0));

			double p = Math.sqrt((cz / len) * 8.0 + 8.0);
			int mx = FastMath.clamp(127 + (int) ((cx / len / p) * 128), 0, 255);
			int my = FastMath.clamp(127 + (int) ((cy / len / p) * 128), 0, 255);
			short normal = (short) ((my << 8) | mx);

			if (key == null)
				key = vertexPool.get();

			key.set((short) (vx1 * scale),
			        (short) (vy1 * scale),
			        (short) (vz1 * scale),
			        normal);

			Vertex vertex = mVertexMap.put(key, false);

			if (vertex == null) {
				key.id = vertexCnt++;
				addVertex(key);
				addIndex(key);
				key = vertexPool.get();
			} else {
				//numIndexHits++;
				addIndex(vertex);
			}

			key.set((short) (vx2 * scale),
			        (short) (vy2 * scale),
			        (short) (vz2 * scale),
			        normal);

			vertex = mVertexMap.put(key, false);

			if (vertex == null) {
				key.id = vertexCnt++;
				addVertex(key);
				addIndex(key);
				key = vertexPool.get();
			} else {
				//numIndexHits++;
				addIndex(vertex);
			}

			key.set((short) (vx3 * scale),
			        (short) (vy3 * scale),
			        (short) (vz3 * scale),
			        (short) normal);

			vertex = mVertexMap.put(key, false);
			if (vertex == null) {
				key.id = vertexCnt++;
				addVertex(key);
				addIndex(key);
				key = vertexPool.get();
			} else {
				//numIndexHits++;
				addIndex(vertex);
			}
		}

		vertexPool.release(key);

		sumVertices = vertexCnt;
	}

	private void addVertex(Vertex vertex) {
		VertexItem vi = mCurVertices;

		if (vi.used == VertexItem.SIZE) {
			mCurVertices.used = VertexItem.SIZE;
			mCurVertices = VertexItem.pool.getNext(vi);
			vi = mCurVertices;
		}

		vi.vertices[vi.used++] = vertex.x;
		vi.vertices[vi.used++] = vertex.y;
		vi.vertices[vi.used++] = vertex.z;
		vi.vertices[vi.used++] = vertex.n;
	}

	private void addIndex(Vertex v) {
		VertexItem vi = mCurIndices[IND_MESH];

		if (vi.used == VertexItem.SIZE) {
			mCurIndices[IND_MESH].used = VertexItem.SIZE;
			mCurIndices[IND_MESH] = VertexItem.pool.getNext(vi);
			vi = mCurIndices[IND_MESH];
		}
		sumIndices++;
		vi.vertices[vi.used++] = (short) v.id;
	}

	//	private void encodeNormal(float v[], int offset) {
	//	    var p = Math.sqrt(cartesian.z * 8.0 + 8.0);
	//	    var result = new Cartesian2();
	//	    result.x = cartesian.x / p + 0.5;
	//	    result.y = cartesian.y / p + 0.5;
	//	    return result;
	//	}
	//	
	public void addNoNormal(MapElement element) {
		if (element.type != GeometryType.TRIS)
			return;

		short[] index = element.index;
		float[] points = element.points;

		/* current vertex id */
		int startVertex = sumVertices;

		/* roof indices for convex shapes */
		int i = mCurIndices[IND_MESH].used;
		short[] indices = mCurIndices[IND_MESH].vertices;
		int first = startVertex;

		for (int k = 0, n = index.length; k < n;) {
			if (index[k] < 0)
				break;

			if (i == VertexItem.SIZE) {
				mCurIndices[IND_MESH].used = VertexItem.SIZE;
				mCurIndices[IND_MESH].next = VertexItem.pool.get();
				mCurIndices[IND_MESH] = mCurIndices[IND_MESH].next;
				indices = mCurIndices[IND_MESH].vertices;
				i = 0;
			}
			indices[i++] = (short) (first + index[k++]);
			indices[i++] = (short) (first + index[k++]);
			indices[i++] = (short) (first + index[k++]);
		}
		mCurIndices[IND_MESH].used = i;

		short[] vertices = mCurVertices.vertices;
		int v = mCurVertices.used;

		int vertexCnt = element.pointPos;

		for (int j = 0; j < vertexCnt;) {
			/* add bottom and top vertex for each point */
			if (v == VertexItem.SIZE) {
				mCurVertices.used = VertexItem.SIZE;
				mCurVertices.next = VertexItem.pool.get();
				mCurVertices = mCurVertices.next;
				vertices = mCurVertices.vertices;
				v = 0;
			}
			/* set coordinate */
			vertices[v++] = (short) (points[j++] * S);
			vertices[v++] = (short) (points[j++] * S);
			vertices[v++] = (short) (points[j++] * S);
			v++;
		}

		mCurVertices.used = v;
		sumVertices += (vertexCnt / 3);
	}

	public void add(MapElement element, float height, float minHeight) {

		short[] index = element.index;
		float[] points = element.points;

		/* 10 cm steps */
		float sfactor = 1 / 10f;
		height *= sfactor;
		minHeight *= sfactor;

		/* match height with ground resultion (meter per pixel) */
		height /= mGroundResolution;
		minHeight /= mGroundResolution;

		boolean complexOutline = false;
		boolean simpleOutline = true;

		/* current vertex id */
		int startVertex = sumVertices;
		int length = 0, ipos = 0, ppos = 0;

		for (int n = index.length; ipos < n; ipos++, ppos += length) {
			length = index[ipos];

			/* end marker */
			if (length < 0)
				break;

			/* start next polygon */
			if (length == 0) {
				startVertex = sumVertices;
				simpleOutline = true;
				complexOutline = false;
				continue;
			}

			/* check: drop last point from explicitly closed rings */
			int len = length;
			if (points[ppos] == points[ppos + len - 2]
			        && points[ppos + 1] == points[ppos + len - 1]) {
				len -= 2;
				log.debug("explicit closed poly " + len);
			}

			/* need at least three points */
			if (len < 6)
				continue;

			/* check if polygon contains inner rings */
			if (simpleOutline && (ipos < n - 1) && (index[ipos + 1] > 0))
				simpleOutline = false;

			boolean convex = addOutline(points, ppos, len, minHeight,
			                            height, simpleOutline);

			if (simpleOutline && (convex || len <= 8)) {
				addRoofSimple(startVertex, len);
			} else if (!complexOutline) {
				complexOutline = true;
				addRoof(startVertex, element, ipos, ppos);
			}
		}
	}

	private void addRoofSimple(int startVertex, int len) {
		/* roof indices for convex shapes */
		int i = mCurIndices[IND_ROOF].used;
		short[] indices = mCurIndices[IND_ROOF].vertices;
		short first = (short) (startVertex + 1);

		for (int k = 0; k < len - 4; k += 2) {
			if (i == VertexItem.SIZE) {
				mCurIndices[IND_ROOF].used = VertexItem.SIZE;
				mCurIndices[IND_ROOF].next = VertexItem.pool.get();
				mCurIndices[IND_ROOF] = mCurIndices[IND_ROOF].next;
				indices = mCurIndices[IND_ROOF].vertices;
				i = 0;
			}

			indices[i++] = first;
			indices[i++] = (short) (first + k + 2);
			indices[i++] = (short) (first + k + 4);
			sumIndices += 3;
		}
		mCurIndices[IND_ROOF].used = i;
	}

	private void addRoof(int startVertex, GeometryBuffer geom, int ipos, int ppos) {
		short[] index = geom.index;
		float[] points = geom.points;

		int len = 0;
		int rings = 0;

		/* get sum of points in polygon */
		for (int i = ipos, n = index.length; i < n && index[i] > 0; i++) {
			len += index[i];
			rings++;
		}

		sumIndices += Tessellator.tessellate(points, ppos, len, index, ipos, rings,
		                                     startVertex + 1, mCurIndices[IND_ROOF]);

		mCurIndices[IND_ROOF] = Inlist.last(mCurIndices[IND_ROOF]);
	}

	private boolean addOutline(float[] points, int pos, int len, float minHeight,
	        float height, boolean convex) {

		/* add two vertices for last face to make zigzag indices work */
		boolean addFace = (len % 4 != 0);
		int vertexCnt = len + (addFace ? 2 : 0);

		short h = (short) height;
		short mh = (short) minHeight;

		float cx = points[pos + len - 2];
		float cy = points[pos + len - 1];
		float nx = points[pos + 0];
		float ny = points[pos + 1];

		/* vector to next point */
		float vx = nx - cx;
		float vy = ny - cy;
		/* vector from previous point */
		float ux, uy;

		float a = (float) Math.sqrt(vx * vx + vy * vy);
		short color1 = (short) ((1 + vx / a) * 127);
		short fcolor = color1;
		short color2 = 0;

		int even = 0;
		int changeX = 0;
		int changeY = 0;
		int angleSign = 0;

		/* vertex offset for all vertices in layer */
		int vOffset = sumVertices;

		short[] vertices = mCurVertices.vertices;
		int v = mCurVertices.used;

		mClipper.clipStart((int) nx, (int) ny);

		for (int i = 2, n = vertexCnt + 2; i < n; i += 2, v += 8) {
			cx = nx;
			cy = ny;

			ux = vx;
			uy = vy;

			/* add bottom and top vertex for each point */
			if (v == VertexItem.SIZE) {
				mCurVertices.used = VertexItem.SIZE;
				mCurVertices.next = VertexItem.pool.get();
				mCurVertices = mCurVertices.next;
				vertices = mCurVertices.vertices;
				v = 0;
			}

			/* set coordinate */
			vertices[v + 0] = vertices[v + 4] = (short) (cx * S);
			vertices[v + 1] = vertices[v + 5] = (short) (cy * S);

			/* set height */
			vertices[v + 2] = mh;
			vertices[v + 6] = h;

			/* get direction to next point */
			if (i < len) {
				nx = points[pos + i + 0];
				ny = points[pos + i + 1];
			} else if (i == len) {
				nx = points[pos + 0];
				ny = points[pos + 1];
			} else { // if (addFace)
				short c = (short) (color1 | fcolor << 8);
				vertices[v + 3] = vertices[v + 7] = c;
				v += 8;
				break;
			}

			vx = nx - cx;
			vy = ny - cy;

			/* set lighting (by direction) */
			a = (float) Math.sqrt(vx * vx + vy * vy);
			color2 = (short) ((1 + vx / a) * 127);

			short c;
			if (even == 0)
				c = (short) (color1 | color2 << 8);
			else
				c = (short) (color2 | color1 << 8);

			vertices[v + 3] = vertices[v + 7] = c;
			color1 = color2;

			/* check if polygon is convex */
			if (convex) {
				/* TODO simple polys with only one concave arc
				 * could be handled without special triangulation */
				if ((ux < 0 ? 1 : -1) != (vx < 0 ? 1 : -1))
					changeX++;
				if ((uy < 0 ? 1 : -1) != (vy < 0 ? 1 : -1))
					changeY++;

				if (changeX > 2 || changeY > 2)
					convex = false;

				float cross = ux * vy - uy * vy;

				if (cross > 0) {
					if (angleSign == -1)
						convex = false;
					angleSign = 1;
				} else if (cross < 0) {
					if (angleSign == 1)
						convex = false;
					angleSign = -1;
				}
			}

			/* check if face is within tile */
			if (mClipper.clipNext((int) nx, (int) ny) == 0) {
				even = ++even % 2;
				continue;
			}

			/* add ZigZagQuadIndices(tm) for sides */
			short vert = (short) (vOffset + (i - 2));
			short s0 = vert++;
			short s1 = vert++;
			short s2 = vert++;
			short s3 = vert++;

			/* connect last to first (when number of faces is even) */
			if (!addFace && i == len) {
				s2 -= len;
				s3 -= len;
			}

			VertexItem it = mCurIndices[even];
			if (it.used == VertexItem.SIZE) {
				it = VertexItem.pool.getNext(it);
				mCurIndices[even] = it;
			}

			int ind = it.used;
			short[] indices = it.vertices;
			indices[ind + 0] = s0;
			indices[ind + 1] = s2;
			indices[ind + 2] = s1;
			indices[ind + 3] = s1;
			indices[ind + 4] = s2;
			indices[ind + 5] = s3;
			it.used += 6;
			sumIndices += 6;

			/* flipp even-odd */
			even = ++even % 2;

			/* add roof outline indices */
			it = mCurIndices[IND_OUTLINE];
			if (it.used == VertexItem.SIZE) {
				it = VertexItem.pool.getNext(it);
				mCurIndices[IND_OUTLINE] = it;
			}
			ind = it.used;
			indices = it.vertices;
			indices[ind + 0] = s1;
			indices[ind + 1] = s3;
			it.used += 2;
			sumIndices += 2;
		}

		mCurVertices.used = v;
		sumVertices += vertexCnt;
		return convex;
	}

	@Override
	public void compile(ShortBuffer vertexBuffer, ShortBuffer indexBuffer) {
		mClipper = null;

		if (mVertexMap != null) {
			vertexPool.releaseAll(mVertexMap.releaseItems());
			mVertexMap = vertexMapPool.release(mVertexMap);
			mVertexMap = null;
		}

		if (sumVertices == 0) {
			return;
		}

		indexOffset = indexBuffer.position();

		for (int i = 0; i <= IND_MESH; i++) {
			for (VertexItem vi = mIndices[i]; vi != null; vi = vi.next) {
				indexBuffer.put(vi.vertices, 0, vi.used);
				numIndices[i] += vi.used;
			}
		}

		//log.debug("INDEX HITS " + numIndexHits + " / " + sumVertices + " / " + sumIndices);

		offset = vertexBuffer.position() * 2;

		for (VertexItem vi = mVertices; vi != null; vi = vi.next)
			vertexBuffer.put(vi.vertices, 0, vi.used);

		clear();
	}

	@Override
	protected void clear() {
		mClipper = null;

		if (mVertexMap != null) {
			vertexPool.releaseAll(mVertexMap.releaseItems());
			mVertexMap = vertexMapPool.release(mVertexMap);
			mVertexMap = null;
		}

		if (mIndices != null) {
			for (int i = 0; i <= IND_MESH; i++)
				mIndices[i] = VertexItem.pool.releaseAll(mIndices[i]);
			mIndices = null;
			mVertices = VertexItem.pool.releaseAll(mVertices);
		}
	}

	public ExtrusionLayer next() {
		return (ExtrusionLayer) next;
	}
}
