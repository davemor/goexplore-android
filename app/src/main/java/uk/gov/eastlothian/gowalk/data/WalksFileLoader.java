package uk.gov.eastlothian.gowalk.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class can be used to load the various part of the data model
 * into the database.
 *
 * Created by davidmorrison on 26/11/14.
 */
public class WalksFileLoader {
    public static final String LOG_TAG = WalksFileLoader.class.getSimpleName();

    static void loadWalksDatabaseFromFiles(Context context) throws IOException{
        Map<Integer, String> descriptions = loadRouteDescriptionsFromCSV(context.getAssets().open("Routes.csv"));
        insertRoutesIntoWalksDatabase(context.getAssets().open("core_paths.json"), descriptions, context);
        WalksFileLoader.loadWildlifeDbFromCSV(context.getAssets().open("Wildlife.csv"), context);
    }

    static void insertRoutesIntoWalksDatabase(InputStream jsonIS, Map<Integer, String> descriptions, Context context) throws IOException
    {
        // read in the json string from the file
        BufferedReader reader = new BufferedReader(new InputStreamReader(jsonIS));
        StringBuilder builder = new StringBuilder();
        String aux = "";
        while ((aux = reader.readLine()) != null) {
            builder.append(aux);
        }
        String routesJsonStr = builder.toString();

        // parse the json in a list of content values for a row in the routes table
        List<ContentValues> valuesList = new ArrayList<ContentValues>();
        try {
            JSONObject routesJson = new JSONObject(routesJsonStr);
            JSONArray paths = routesJson.getJSONArray("features");
            for (int idx=0; idx < paths.length(); ++idx) {

                // get the json objects out the document tree
                JSONObject path = paths.getJSONObject(idx);
                JSONObject properties = path.getJSONObject("properties");
                JSONArray coordinatesArray = path.getJSONObject("geometry")
                                                 .getJSONArray("coordinates");

                // get the values out of the json object
                int routeNumber = properties.getInt("route_no");
                String coordinates = coordinatesArray.toString();
                String pathType = properties.getString("path_type");
                int length = properties.getInt("length");
                String surface = properties.getString("surface");
                if(surface.equalsIgnoreCase("null")) {
                    surface = "unknown";
                }

                String description = descriptions.get(routeNumber);
                if(description == null || description.equalsIgnoreCase("null")) {
                    description = "no description available";
                }

                // associate the values with table names
                ContentValues values = new ContentValues();
                values.put(WalksContract.RouteEntry.COLUMN_ROUTE_NUMBER, routeNumber);
                values.put(WalksContract.RouteEntry.COLUMN_COORDINATES, coordinates);
                values.put(WalksContract.RouteEntry.COLUMN_PATH_TYPE, pathType);
                values.put(WalksContract.RouteEntry.COLUMN_LENGTH, length);
                values.put(WalksContract.RouteEntry.COLUMN_SURFACE, surface);
                values.put(WalksContract.RouteEntry.COLUMN_DESCRIPTION, description);
                valuesList.add(values);
            }
        } catch (JSONException e) {
            Log.d(LOG_TAG, "There was an error parsing the core path json.", e);
        }

        // bulk insert the values into the content provider
        ContentValues [] contentValues = valuesList.toArray(new ContentValues[valuesList.size()]);
        for(int idx=0; idx < contentValues.length-1; ++idx) {
            Log.d(LOG_TAG, contentValues[idx].toString());
        }
        context.getContentResolver().bulkInsert(WalksContract.RouteEntry.CONTENT_URI, contentValues);
    }

    static Map<Integer, String> loadRouteDescriptionsFromCSV(InputStream inStream) {
        Map<Integer, String> rtnMap = new HashMap<Integer, String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inStream));
        try {
            reader.readLine(); // read and throw away the first line
            String line;
            while ((line = reader.readLine()) != null) {
                String[] rowData = line.split(",");
                int routeNumber = Integer.parseInt(rowData[0]);
                String description = rowData[1];
                rtnMap.put(routeNumber, description);
            }
        } catch (IOException ex) {
            Log.d(LOG_TAG, "Error while loading the route descriptions.", ex);
        } finally {
            try {
                inStream.close();
            }
            catch (IOException e) {
                Log.d(LOG_TAG, "Error while closing the route descriptions csv input stream.", e);
            }
        }
        return rtnMap;
    }

    static void loadWildlifeDbFromCSV(InputStream inStream, Context context) {
        // List<ContentValues> valuesList = new ArrayList<ContentValues>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inStream));
        try {
            reader.readLine();
            reader.readLine();
            String line;
            while((line = reader.readLine()) != null) {
                String[] rowData = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");

                if(rowData.length >= 5) {

                    // get the values out of the row
                    String name = rowData[1];
                    String category = rowData[2];
                    String description = rowData[3];
                    String foundOnRoutes = rowData[4];
                    String whenSeen = rowData[5];
                    String imageFile = "no_image";

                    // images are optional
                    if (rowData.length > 6) {
                        imageFile = rowData[6];
                    }

                    // assign them to database table names
                    ContentValues values = new ContentValues();
                    values.put(WalksContract.WildlifeEntry.COLUMN_WILDLIFE_NAME, name);
                    values.put(WalksContract.WildlifeEntry.COLUMN_CATEGORY, category);
                    values.put(WalksContract.WildlifeEntry.COLUMN_DESCRIPTION, description);
                    values.put(WalksContract.WildlifeEntry.COLUMN_WHEN_SEEN, whenSeen);
                    values.put(WalksContract.WildlifeEntry.COLUMN_IMAGE_NAME, imageFile);

                    // insert the route into the content resolver
                    Uri wildlifeUri = context.getContentResolver().insert(WalksContract.WildlifeEntry.CONTENT_URI, values);
                    long wildlifeId = Long.parseLong(WalksContract.WildlifeEntry.getWildlifeFromUri(wildlifeUri));

                    // get the route numbers of the routes the wildlife is found on
                    // remove the first and last character (quotes) then split on comma
                    String[] foundOnRoutesArr = foundOnRoutes.substring(1, foundOnRoutes.length() - 1)
                            .split(",");

                    // get the ids of those routes from the database
                    // note - we are assuming that the routes have been inserted already
                    List<ContentValues> wildlifeOnRouteValues = new ArrayList<ContentValues>();
                    for (String routeNumStr : foundOnRoutesArr) {
                        // get the route id from the route number
                        try {
                            routeNumStr = routeNumStr.replace('.', ' ').trim();
                            long routeNum = Long.parseLong(routeNumStr);
                            Cursor cursor = context.getContentResolver().query(
                                    WalksContract.RouteEntry.CONTENT_URI,
                                    new String[]{WalksContract.RouteEntry._ID},
                                    WalksContract.RouteEntry.COLUMN_ROUTE_NUMBER + " LIKE ?",
                                    new String[]{routeNum + "%"},
                                    null);
                            if (cursor.moveToFirst()) {
                                ContentValues rowValues = new ContentValues();
                                int idx = cursor.getColumnIndex(WalksContract.RouteEntry._ID);
                                long routeId = cursor.getLong(idx);
                                rowValues.put(WalksContract.WildlifeOnRouteEntry.COLUMN_ROUTE_KEY, routeId);
                                rowValues.put(WalksContract.WildlifeOnRouteEntry.COLUMN_WILDLIFE_KEY, wildlifeId);
                                wildlifeOnRouteValues.add(rowValues);

                            } else {
                                Log.d(LOG_TAG, "Error while loading wildlife.  Cannot find route number " + routeNumStr + ".");
                            }
                            cursor.close();
                        } catch (NumberFormatException e) {
                            Log.d(LOG_TAG, "Invalid route number for wildlife " + routeNumStr + ".");
                        }
                    }
                    // insert the values into the database
                    context.getContentResolver().bulkInsert(WalksContract.WildlifeOnRouteEntry.CONTENT_URI,
                            wildlifeOnRouteValues.toArray(new ContentValues[wildlifeOnRouteValues.size()]));
                }

            }
        } catch (IOException ex) {
            Log.d(LOG_TAG, "Error while loading the wildlife.", ex);
        } finally {
            try {
                inStream.close();
            }
            catch (IOException e) {
                Log.d(LOG_TAG, "Error while closing the wildlife csv input stream.", e);
            }
        }
    }
}
