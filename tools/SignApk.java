import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/** Host-side APK signing; this class is not included in the module APK. */
public final class SignApk {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            store.load(input, "android".toCharArray());
        }
        var signer = new ApkSigner.SignerConfig.Builder(
                "gameboxvisionfix",
                (PrivateKey) store.getKey("gameboxvisionfix", "android".toCharArray()),
                List.of((X509Certificate) store.getCertificate("gameboxvisionfix"))).build();
        new ApkSigner.Builder(List.of(signer))
                .setInputApk(new File(args[1]))
                .setOutputApk(new File(args[2]))
                .setMinSdkVersion(29)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign();

        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[2]))
                .setMinCheckedPlatformVersion(29)
                .build()
                .verify();
        ApkVerifier.Result v2Result = new ApkVerifier.Builder(new File(args[2]))
                .setMinCheckedPlatformVersion(27)
                .setMaxCheckedPlatformVersion(27)
                .build()
                .verify();
        if (!result.isVerified() || !v2Result.isVerifiedUsingV2Scheme()
                || !result.isVerifiedUsingV3Scheme()) {
            throw new IllegalStateException("Signature verification failed");
        }
        System.out.println("APK signature verified: v2=true, v3=true");
    }
}
