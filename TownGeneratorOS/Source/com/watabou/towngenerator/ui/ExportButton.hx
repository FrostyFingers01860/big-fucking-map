package com.watabou.towngenerator.ui;

import haxe.io.Bytes;
import haxe.io.Path;
import lime.app.Application;
import lime.system.System;
import lime.ui.FileDialog;

import com.watabou.towngenerator.building.Model;

// Saves the current city as JSON through the system's Save dialog, starting in
// Documents\Town Generator, and reports where the file went (or what went wrong).
class ExportButton extends Button {

	public function new() {
		super( "Save JSON" );
		click.add( onClick );
	}

	private function onClick():Void {
		try {
			var json = CityExporter.toJson( Model.instance );
			var dialog = new FileDialog();
			dialog.onSave.add( function( path:String ) message( "Saved the city to:\n\n" + path, "City saved" ) );
			dialog.save( Bytes.ofString( json ), "json", nativePath( Path.join( [exportFolder(), 'town-${StateManager.seed}.json'] ) ),
				"Save city as JSON" );
		} catch (e:Dynamic) {
			message( "Could not save the city:\n\n" + Std.string( e ), "Save failed" );
		}
	}

	private static function exportFolder():String {
		var documents = System.documentsDirectory;
		if (documents == null || documents == "") return "";
		var folder = Path.join( [documents, "Town Generator"] );
		#if sys
		try { if (!sys.FileSystem.exists( folder )) sys.FileSystem.createDirectory( folder ); } catch (e:Dynamic) return documents;
		#end
		return folder;
	}

	// The Windows Save dialog ignores a starting folder written with forward slashes.
	private static function nativePath( path:String ):String {
		#if windows
		return path.split( "/" ).join( "\\" );
		#else
		return path;
		#end
	}

	private static function message( text:String, title:String ):Void {
		var window = Application.current != null ? Application.current.window : null;
		if (window != null) window.alert( text, title ) else trace( title + ": " + text );
	}
}
