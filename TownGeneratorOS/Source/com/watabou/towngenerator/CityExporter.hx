package com.watabou.towngenerator;

import haxe.Json;
import openfl.geom.Point;

import com.watabou.geom.Polygon;

import com.watabou.towngenerator.building.CurtainWall;
import com.watabou.towngenerator.building.Model;
import com.watabou.towngenerator.building.Patch;

// Serializes the current city model to JSON. Coordinates are in the generator's own map units,
// centred on the city; every polygon is a list of [x, y] vertices.
class CityExporter {

	public static function toJson( model:Model ):String {
		var wards = [];
		for (patch in (model.patches != null ? model.patches : [])) {
			var ward = patch.ward;
			wards.push( {
				type		: ward != null ? Type.getClassName( Type.getClass( ward ) ).split( "." ).pop() : null,
				label		: ward != null ? ward.getLabel() : null,
				withinCity	: patch.withinCity,
				withinWalls	: patch.withinWalls,
				isCitadel	: patch == model.citadel,
				isPlaza		: patch == model.plaza,
				shape		: polygon( patch.shape ),
				buildings	: ward != null ? polygons( ward.geometry ) : []
			} );
		}

		var data = {
			generator	: "Medieval Fantasy City Generator (TownGeneratorOS)",
			seed		: StateManager.seed,
			size		: StateManager.size,
			cityRadius	: round( model.cityRadius ),
			center		: point( model.center ),
			wall		: wall( model.wall ),
			border		: wall( model.border ),
			gates		: points( model.gates ),
			streets		: polygons( model.streets ),
			roads		: polygons( model.roads ),
			arteries	: polygons( model.arteries ),
			wards		: wards
		};
		return Json.stringify( data, null, "  " );
	}

	private static function wall( wall:CurtainWall ):Dynamic {
		if (wall == null) return null;
		return {
			shape	: polygon( wall.shape ),
			// segments[i] is false where the wall is open between vertex i and i+1
			segments: wall.segments != null ? wall.segments : [],
			gates	: points( wall.gates ),
			// Towers exist only on real walls; an unwalled town's border has none.
			towers	: points( wall.towers )
		};
	}

	private static function polygons( list:Array<Polygon> ):Array<Array<Array<Float>>>
		return list == null ? [] : [for (poly in list) polygon( poly )];

	private static function points( list:Array<Point> ):Array<Array<Float>>
		return list == null ? [] : [for (p in list) point( p )];

	private static function polygon( poly:Polygon ):Array<Array<Float>>
		return poly == null ? [] : [for (p in (poly:Array<Point>)) point( p )];

	private static function point( p:Point ):Array<Float>
		return p == null ? null : [round( p.x ), round( p.y )];

	private static inline function round( v:Float ):Float
		return Math.round( v * 1000 ) / 1000;
}
