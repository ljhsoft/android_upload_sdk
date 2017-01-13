/*
 * Copyright (c) 2016 Samsung Electronics America
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.samsung.msca.samsungvr.sdk;

import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

class UserLiveEventSegmentImpl implements UserLiveEventSegment {

    private static final String TAG = Util.getLogTag(UserLiveEventSegmentImpl.class);
    private static final boolean DEBUG = Util.DEBUG;

    private final UserLiveEventImpl mUserLiveEvent;
    private final String mSegmentId;

    UserLiveEventSegmentImpl(UserLiveEventImpl userLiveEvent, String segmentId) {
        mUserLiveEvent = userLiveEvent;
        mSegmentId = segmentId;
    }

    private String mInitialSignedUrl;
    private boolean mUploading = false;

    void setIsUploading(boolean isUploading) {
        synchronized (this) {
            mUploading = isUploading;
        }
    }

    boolean uploadContent(AtomicBoolean cancelHolder, ParcelFileDescriptor source, String initialSignedUrl,
                          ResultCallbackHolder callbackHolder) {
        synchronized (this) {
            if (mUploading) {
                return false;
            }
            mInitialSignedUrl = initialSignedUrl;
            return retryUploadNoLock(cancelHolder, source, (UserLiveEvent.Result.UploadSegment)callbackHolder.getCallbackNoLock(),
                    callbackHolder.getHandlerNoLock(), callbackHolder.getClosureNoLock());
        }
    }

    void onUploadComplete() {
        synchronized (this) {
            mInitialSignedUrl = null;
            mUploading = false;
        }
    }

    @Override
    public boolean cancelUpload(Object closure) {
        if (DEBUG) {
            Log.d(TAG, "Cancelled segment upload requested this: " + this);
        }
        synchronized (this) {
            if (!mUploading) {
                return false;
            }
            return mUserLiveEvent.cancelUploadSegment(closure);
        }
    }

    private boolean retryUploadNoLock(AtomicBoolean cancelHolder, ParcelFileDescriptor source,
        UserLiveEvent.Result.UploadSegment callback, Handler handler, Object closure) {
        if (null == mSegmentId || null == mInitialSignedUrl) {
            return false;
        }
        AsyncWorkQueue<ClientWorkItemType, ClientWorkItem<?>> workQueue =
                mUserLiveEvent.getContainer().getContainer().getAsyncUploadQueue();

        WorkItemSegmentContentUpload workItem = workQueue.obtainWorkItem(WorkItemSegmentContentUpload.TYPE);
        workItem.set(cancelHolder, this, mUserLiveEvent, source, mInitialSignedUrl, mSegmentId, callback, handler, closure);
        mUploading = workQueue.enqueue(workItem);
        return mUploading;
    }


    static class WorkItemSegmentContentUpload extends UserLiveEventImpl.WorkItemSegmentUploadBase {


        @Override
        protected void dispatchCounted(Util.CallbackNotifier notifier) {
            super.dispatchCounted(notifier);
            mSegment.setIsUploading(false);
        }

        @Override
        protected void dispatchCancelled() {
            super.dispatchCancelled();
            mSegment.onUploadComplete();
        }

        @Override
        protected void dispatchSuccess() {
            super.dispatchSuccess();
            mSegment.onUploadComplete();
        }

        static final ClientWorkItemType TYPE = new ClientWorkItemType() {
            @Override
            public WorkItemSegmentContentUpload newInstance(APIClientImpl apiClient) {
                return new WorkItemSegmentContentUpload(apiClient);
            }
        };

        WorkItemSegmentContentUpload(APIClientImpl apiClient) {
            super(apiClient, TYPE);
        }

        private UserLiveEventSegmentImpl mSegment;
        private ParcelFileDescriptor mSource;
        private String mSegmentId, mInitialSignedUrl;
        private UserLiveEventImpl mUserLiveEvent;

        synchronized WorkItemSegmentContentUpload set(AtomicBoolean cancelHolder,
            UserLiveEventSegmentImpl segment, UserLiveEventImpl userLiveEvent,
            ParcelFileDescriptor source, String initialSignedUrl, String segmentId,
            UserLiveEvent.Result.UploadSegment callback, Handler handler, Object closure) {

            super.set(cancelHolder, callback, handler, closure);
            mSegment = segment;
            mSegmentId = segmentId;
            mUserLiveEvent = userLiveEvent;
            mSource = source;
            mInitialSignedUrl = initialSignedUrl;
            return this;
        }

        @Override
        protected synchronized void recycle() {
            super.recycle();
            mSegment = null;
            mSource = null;
            mInitialSignedUrl = null;
        }

        private static final String TAG = Util.getLogTag(WorkItemSegmentContentUpload.class);

        @Override
        public void onRun() throws Exception {

            User user = mUserLiveEvent.getUser();
            ParcelFileDescriptor source = mSource;
            UserLiveEventSegmentImpl segment = mSegment;

            String headers0[][] = {
                    /*
                     * Content length must be the first header. Index 0 is used to set the
                     * real length later
                     */
                {HEADER_CONTENT_LENGTH, "0"},
                {HEADER_CONTENT_TYPE, "video/MP2T"},
                {HEADER_CONTENT_TRANSFER_ENCODING, "binary"},
                {UserImpl.HEADER_SESSION_TOKEN, user.getSessionToken()},
                {APIClientImpl.HEADER_API_KEY, mAPIClient.getApiKey()},
            };

            long length = source.getStatSize();
            long chunkSize = 65535;
            int numChunks = 1 + (int)(length / chunkSize);

            FileInputStream buf = null;

            try {

                buf = new FileInputStream(source.getFileDescriptor());
                FileChannel channel = buf.getChannel();
                channel.position(0);

                SplitStream split = new SplitStream(buf, length, chunkSize) {
                    @Override
                    protected boolean canContinue() {
                        return !isCancelled();
                    }
                };

                headers0[0][1] = String.valueOf(length);
                HttpPlugin.PutRequest uploadRequest = newRequest(mInitialSignedUrl, HttpMethod.PUT, headers0);
                if (null == uploadRequest) {
                    dispatchFailure(VR.Result.STATUS_HTTP_PLUGIN_NULL_CONNECTION);
                    return;
                }

                for (int i = 0; i < numChunks; i++) {

                    if (isCancelled()) {
                        dispatchCancelled();
                        return;
                    }

                    float progress = 100f * ((float) (i) / (float) numChunks);

                    dispatchUncounted(new ProgressCallbackNotifier(progress).setNoLock(mCallbackHolder));

                    if (DEBUG) {
                        Log.d(TAG, "Uploading chunk: " + i + " url: " + mInitialSignedUrl);
                    }
                    split.renew();
                    try {
                        writeHttpStream(uploadRequest, split);
                    } catch (Exception ex) {
                        if (isCancelled()) {
                            dispatchCancelled();
                            return;
                        }
                        throw ex;
                    }
                }
                int rsp2 = getResponseCode(uploadRequest);

                if (!isHTTPSuccess(rsp2)) {
                    dispatchFailure(User.Result.UploadVideo.STATUS_CHUNK_UPLOAD_FAILED);
                    return;
                }
                dispatchUncounted(new ProgressCallbackNotifier(100f).setNoLock(mCallbackHolder));
                if (DEBUG) {
                    Log.d(TAG, "After successful upload, bytes remaining: " + split.availableAsLong());
                }

                destroy(uploadRequest);
                dispatchSuccess();

            } finally {
                if (null != buf) {
                    buf.close();
                }
            }

        }

    }
}
