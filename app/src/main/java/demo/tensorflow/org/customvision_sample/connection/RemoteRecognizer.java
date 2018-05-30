package demo.tensorflow.org.customvision_sample.connection;

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RemoteRecognizer extends AsyncTask<Void, Void, Boolean> {
    private ArrayList<String> mBlobUrls = new ArrayList<>();
    private String mLabel;
    private final String RECOGNIZER_ENDPOINT = "";

    public RemoteRecognizer(String blobUrl) {
        mBlobUrls.add(blobUrl);
        mLabel = null;
    }

    public RemoteRecognizer(ArrayList<String> blobUrls, String label) {
        mBlobUrls = blobUrls;
        mLabel = label;
    }

    @Override protected Boolean doInBackground(Void... voids) {
        try {
            JSONObject json = new JSONObject();
            json.put("name", mLabel);
            JSONArray jsonArray = new JSONArray();
            int i = 0;
            for (String blobUrl : mBlobUrls) {
                jsonArray.put(i, blobUrl);
                i++;
            }
            json.put("blobs", jsonArray);
            RequestBody requestBody = RequestBody.create(MediaType.parse("application/json"), json.toString());
            HttpUrl.Builder urlBuilder = HttpUrl.parse(RECOGNIZER_ENDPOINT).newBuilder();
            String url = urlBuilder.build().toString();
            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();
            OkHttpClient client = new OkHttpClient();
            Response response = client.newCall(request).execute();
            return response.isSuccessful();
        } catch (Exception e) {
            Log.i("RECOGNIZER", e.getMessage());
        }
        return true;
    }
}
