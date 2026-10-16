import com.watabou.towngenerator.building.Model;
import com.watabou.geom.Polygon;
import openfl.geom.Point;

/**
 * Exposes watabou's Medieval Fantasy City Generator (TownGeneratorOS) to Node as
 * generateTown(size, seed). Compiled to towngen.js by build-towngen.bat; driven by
 * generate-station-towns.js. Returns plain arrays so Node can pack them compactly.
 */
@:expose @:keep
class StationTownGen {
	static function main() {
		// The generator logs every retry ("Bad citadel shape!" etc.); keep batch output clean.
		haxe.Log.trace = function( v:Dynamic, ?infos:haxe.PosInfos ) {};
	}

	public static function generateTown( size:Int, seed:Int ):Dynamic {
		var model = new Model( size, seed );
		var wards = [];
		for (patch in model.patches) {
			if (!patch.withinCity) continue;
			var ward = patch.ward;
			wards.push( {
				type		: ward == null ? "Empty" : Type.getClassName( Type.getClass( ward ) ).split( "." ).pop(),
				walled		: patch.withinWalls,
				shape		: flat( patch.shape ),
				buildings	: ward == null || ward.geometry == null ? [] : [for (b in ward.geometry) flat( b )]
			} );
		}
		return {
			radius	: model.cityRadius,
			wards	: wards,
			wall	: model.wall == null ? null : flat( model.wall.shape ),
			towers	: model.wall == null || model.wall.towers == null ? [] : flatPoints( model.wall.towers ),
			gates	: model.gates == null ? [] : flatPoints( model.gates ),
			streets	: model.streets == null ? [] : [for (s in model.streets) flat( s )],
			roads	: model.roads == null ? [] : [for (r in model.roads) flat( r )]
		};
	}

	static function flat( poly:Polygon ):Array<Float> {
		var out = [];
		if (poly != null) for (p in (poly:Array<Point>)) { out.push( p.x ); out.push( p.y ); }
		return out;
	}

	static function flatPoints( points:Array<Point> ):Array<Float> {
		var out = [];
		for (p in points) { out.push( p.x ); out.push( p.y ); }
		return out;
	}
}
