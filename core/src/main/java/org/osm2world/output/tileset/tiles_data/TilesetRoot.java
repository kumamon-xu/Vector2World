package org.osm2world.output.tileset.tiles_data;

public class TilesetRoot {
    private TilesetAsset asset;
    /** the error introduced if this tileset is not rendered; required by the 3D Tiles spec */
    private Number geometricError = 0;
    private TilesetParentEntry root;

    public TilesetRoot() {
    }

    public TilesetAsset getAsset() {
        return asset;
    }
    public void setAsset(TilesetAsset asset) {
        this.asset = asset;
    }

    public Number getGeometricError() {
        return geometricError;
    }
    public void setGeometricError(Number geometricError) {
        this.geometricError = geometricError;
    }

    public TilesetParentEntry getRoot() {
        return root;
    }
    public void setRoot(TilesetParentEntry root) {
        this.root = root;
    }
    
}
