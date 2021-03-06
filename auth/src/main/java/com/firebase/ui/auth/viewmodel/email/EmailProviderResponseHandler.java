package com.firebase.ui.auth.viewmodel.email;

import android.app.Application;
import android.util.Log;

import com.firebase.ui.auth.ErrorCodes;
import com.firebase.ui.auth.FirebaseUiException;
import com.firebase.ui.auth.IdpResponse;
import com.firebase.ui.auth.data.model.IntentRequiredException;
import com.firebase.ui.auth.data.model.Resource;
import com.firebase.ui.auth.data.model.User;
import com.firebase.ui.auth.data.remote.ProfileMerger;
import com.firebase.ui.auth.ui.email.WelcomeBackEmailLinkPrompt;
import com.firebase.ui.auth.ui.email.WelcomeBackPasswordPrompt;
import com.firebase.ui.auth.ui.idp.WelcomeBackIdpPrompt;
import com.firebase.ui.auth.util.data.AuthOperationManager;
import com.firebase.ui.auth.util.data.ProviderUtils;
import com.firebase.ui.auth.util.data.TaskFailureLogger;
import com.firebase.ui.auth.viewmodel.RequestCodes;
import com.firebase.ui.auth.viewmodel.SignInViewModelBase;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import static com.firebase.ui.auth.AuthUI.EMAIL_LINK_PROVIDER;

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EmailProviderResponseHandler extends SignInViewModelBase {
    private static final String TAG = "EmailProviderResponseHa";

    public EmailProviderResponseHandler(Application application) {
        super(application);
    }

    public void startSignIn(@NonNull final IdpResponse response, @NonNull final String password) {
        if (!response.isSuccessful()) {
            setResult(Resource.<IdpResponse>forFailure(response.getError()));
            return;
        }
        if (!response.getProviderType().equals(EmailAuthProvider.PROVIDER_ID)) {
            throw new IllegalStateException(
                    "This handler can only be used with the email provider");
        }
        setResult(Resource.<IdpResponse>forLoading());

        final AuthOperationManager authOperationManager = AuthOperationManager.getInstance();
        final String email = response.getEmail();
        authOperationManager.createOrLinkUserWithEmailAndPassword(getAuth(),
                getArguments(),
                email,
                password)
                .continueWithTask(new ProfileMerger(response))
                .addOnFailureListener(new TaskFailureLogger(TAG, "Error creating user"))
                .addOnSuccessListener(new OnSuccessListener<AuthResult>() {
                    @Override
                    public void onSuccess(AuthResult result) {
                        handleSuccess(response, result);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        if (e instanceof FirebaseAuthUserCollisionException) {
                            if (authOperationManager.canUpgradeAnonymous(getAuth(),
                                    getArguments())) {
                                AuthCredential credential = EmailAuthProvider.getCredential(email,
                                        password);
                                handleMergeFailure(credential);
                            } else {
                                Log.w(TAG, "Got a collision error during a non-upgrade flow", e);

                                // Collision with existing user email without anonymous upgrade
                                // it should be very hard for the user to even get to this error
                                // due to CheckEmailFragment.
                                ProviderUtils.fetchTopProvider(getAuth(), getArguments(), email)
                                        .addOnSuccessListener(new StartWelcomeBackFlow(email))
                                        .addOnFailureListener(new OnFailureListener() {
                                            @Override
                                            public void onFailure(@NonNull Exception e) {
                                                setResult(Resource.<IdpResponse>forFailure(e));
                                            }
                                        });
                            }
                        } else {
                            setResult(Resource.<IdpResponse>forFailure(e));
                        }
                    }
                });
    }

    private class StartWelcomeBackFlow implements OnSuccessListener<String> {
        private final String mEmail;

        public StartWelcomeBackFlow(String email) {
            mEmail = email;
        }

        @Override
        public void onSuccess(@Nullable String provider) {
            if (provider == null) {
                Log.w(TAG, "No providers known for user ("
                        + mEmail
                        + ") this email address may be reserved.");
                setResult(Resource.<IdpResponse>forFailure(
                        new FirebaseUiException(ErrorCodes.UNKNOWN_ERROR)));
                return;
            }

            if (EmailAuthProvider.PROVIDER_ID.equalsIgnoreCase(provider)) {
                setResult(Resource.<IdpResponse>forFailure(new IntentRequiredException(
                        WelcomeBackPasswordPrompt.createIntent(
                                getApplication(),
                                getArguments(),
                                new IdpResponse.Builder(new User.Builder(
                                        EmailAuthProvider.PROVIDER_ID, mEmail).build()
                                ).build()),
                        RequestCodes.WELCOME_BACK_EMAIL_FLOW
                )));
            } else if (EMAIL_LINK_PROVIDER.equalsIgnoreCase(provider)) {
                setResult(Resource.<IdpResponse>forFailure(new IntentRequiredException(
                        WelcomeBackEmailLinkPrompt.createIntent(
                                getApplication(),
                                getArguments(),
                                new IdpResponse.Builder(new User.Builder(
                                        EMAIL_LINK_PROVIDER, mEmail).build()
                                ).build()),
                        RequestCodes.WELCOME_BACK_EMAIL_LINK_FLOW
                )));
            } else {
                setResult(Resource.<IdpResponse>forFailure(new IntentRequiredException(
                        WelcomeBackIdpPrompt.createIntent(
                                getApplication(),
                                getArguments(),
                                new User.Builder(provider, mEmail).build()),
                        RequestCodes.WELCOME_BACK_IDP_FLOW
                )));
            }
        }
    }
}
