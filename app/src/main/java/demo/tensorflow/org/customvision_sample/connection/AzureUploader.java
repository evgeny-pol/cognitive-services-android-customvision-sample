package demo.tensorflow.org.customvision_sample.connection;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.OperationContext;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobContainerPublicAccessType;
import com.microsoft.azure.storage.blob.BlobRequestOptions;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.ArrayList;

public class AzureUploader {

    private Context mContext;
    private final String STORAGE_CONNECTION_STRING = "DefaultEndpointsProtocol=https;AccountName=blobcodepush;AccountKey=Lue8YHfRNrsXCgbBGeIb5N+n9W0fOrUPjZj7jI0cFgNVsequeHruigR+68nLGv2VOhYGDrX9+VwYXfEgWWlj4w==;EndpointSuffix=core.windows.net";
    private final String TRAINING_CONTAINER_NAME = "training-container";

    public AzureUploader(Context context) {
        mContext = context;
    }

    private File saveBitmapToFile(Bitmap image) throws IOException {
        File file = new File(mContext.getCacheDir(), "tempImage.png");
        file.createNewFile();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG, 0 /*ignored for PNG*/, byteArrayOutputStream);
        byte[] bitmapData = byteArrayOutputStream.toByteArray();
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        fileOutputStream.write(bitmapData);
        fileOutputStream.flush();
        fileOutputStream.close();
        return file;
    }

    private CloudBlobContainer getOrCreateContainer(String name) throws InvalidKeyException, URISyntaxException, StorageException{
        CloudStorageAccount storageAccount = CloudStorageAccount.parse(STORAGE_CONNECTION_STRING);
        CloudBlobClient blobClient = storageAccount.createCloudBlobClient();
        CloudBlobContainer container = blobClient.getContainerReference(name);
        container.createIfNotExists(BlobContainerPublicAccessType.CONTAINER, new BlobRequestOptions(), new OperationContext());
        return container;
    }

    public ArrayList<String> sendTrainingImages(ContentResolver contentResolver, String label, ArrayList<Uri> uris) throws Exception {
        ArrayList<String> azureUris = new ArrayList<>();
        CloudBlobContainer container = getOrCreateContainer(TRAINING_CONTAINER_NAME);

        int countLabels = 0;
        for (ListBlobItem blobItem : container.listBlobs(label)) {
            countLabels ++;
        }

        int startLabel = countLabels;
        for (Uri uri: uris) {
            Bitmap bitmap = getBitmapFromUri(contentResolver, uri);
            File sourceFile = this.saveBitmapToFile(bitmap);
            CloudBlockBlob blob = container.getBlockBlobReference(label + "/" + startLabel + ".png");
            blob.uploadFromFile(sourceFile.getAbsolutePath());
            startLabel++;
        }

        int existing = -1;
        for (ListBlobItem blobItem : container.listBlobs(label)) {
            existing ++;
            if (existing >= countLabels) {
                azureUris.add(blobItem.getUri().toString());
            }
        }
        return azureUris;
    }

    private Bitmap getBitmapFromUri(ContentResolver contentResolver, Uri uri) throws Exception {
        ParcelFileDescriptor parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r");
        FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
        Bitmap bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
        parcelFileDescriptor.close();
        return bitmap;
    }
}
