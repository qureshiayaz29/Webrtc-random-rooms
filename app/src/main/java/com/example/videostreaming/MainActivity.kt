package com.example.videostreaming

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity() {

    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var localVideoTrack: VideoTrack
    private lateinit var localAudioTrack: AudioTrack
    private lateinit var videoCapturer: VideoCapturer
    private lateinit var localStream: MediaStream
    private lateinit var peerConnection: PeerConnection
    private lateinit var db: DatabaseReference
    private var roomId: String = "room123" // Replace with dynamic matching later
    private var isCaller = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:your.turn.server:3478")
            .setUsername("username")
            .setPassword("password")
            .createIceServer()
    )

    private val sdpConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = FirebaseDatabase.getInstance().reference

        // Views
        localView = findViewById(R.id.localView)
        remoteView = findViewById(R.id.remoteView)

        // Permissions
        if (allPermissionsGranted()) {
            initWebRTC()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO
                ),
                1
            )
        }
        findViewById<Button>(R.id.btnStartMatching).setOnClickListener {
            startCall()
            listenForCalleeCandidates()
        }
        findViewById<Button>(R.id.joinCallButton).setOnClickListener {
            joinCall()
            listenForCallerCandidates()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        ).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initWebRTC() {
        // Initialize EGL for video rendering
        val eglBase = EglBase.create()

        // Initialize both views
        localView.init(eglBase.eglBaseContext, null)
        localView.setMirror(true)
        localView.setZOrderMediaOverlay(true) // Ensure local view is on top
        localView.setEnableHardwareScaler(true)

        remoteView.init(eglBase.eglBaseContext, null)
        remoteView.setMirror(false)
        remoteView.setEnableHardwareScaler(true)
        
        // Make sure both views are visible
        localView.visibility = android.view.View.VISIBLE
        remoteView.visibility = android.view.View.VISIBLE

        // Initialize PeerConnectionFactory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory
            .builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Create Video Capturer
        videoCapturer = createCameraCapturer(Camera1Enumerator(false))
            ?: throw RuntimeException("Failed to create video capturer")

        val videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            this,
            videoSource.capturerObserver
        )
        videoCapturer.startCapture(640, 480, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource)
        localVideoTrack.addSink(localView)

        // Audio
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)

        // Local Media Stream
        localStream = peerConnectionFactory.createLocalMediaStream("mediaStream")
        localStream.addTrack(localVideoTrack)
        localStream.addTrack(localAudioTrack)

        // create peer connection
        createPeerConnection()
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }
        return null
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection =
            peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d("WebRTC", "New IceCandidate: $candidate")
                        val candidateMap = mapOf(
                            "sdpMid" to candidate.sdpMid,
                            "sdpMLineIndex" to candidate.sdpMLineIndex,
                            "candidate" to candidate.sdp
                        )
                        val candidateType = if (isCaller) "callerCandidates" else "calleeCandidates"
                        db.child("rooms").child(roomId).child(candidateType).push()
                            .setValue(candidateMap)
                    }
                }

                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}

                override fun onAddStream(p0: MediaStream?) {}

                override fun onTrack(transceiver: RtpTransceiver?) {
                    runOnUiThread {
                        val track = transceiver?.receiver?.track()
                        if (track is VideoTrack) {
                            Log.d("WebRTC", "Remote video track received")
                            track.addSink(remoteView)
                            Log.d("WebRTC", "Remote video track set")
                        }
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    Log.d("WebRTC", "ICE Connection State: $newState")
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    Log.d("WebRTC", "Connection State: $newState")
                }

                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                    Log.d("WebRTC", "Signaling State: $newState")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            })!!

        peerConnection.addTrack(localVideoTrack, listOf("stream1"))
        peerConnection.addTrack(localAudioTrack, listOf("stream1"))
    }

    private fun startCall() {
        isCaller = true
        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d("WebRTC", "Local description set. Send offer to Firebase.")
                            Log.d("WebRTC", "Remote description set successfully")

                            db.child("rooms").child(roomId).child("offer").setValue(
                                mapOf(
                                    "type" to desc.type.canonicalForm(),
                                    "sdp" to desc.description
                                )
                            )

                            listenForAnswer()
                        }

                        override fun onSetFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }
            }

            override fun onCreateFailure(reason: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, sdpConstraints)
    }

    private fun listenForAnswer() {
        db.child("rooms").child(roomId).child("answer").addValueEventListener(object :
            ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.child("sdp").getValue(String::class.java)
                val type = snapshot.child("type").getValue(String::class.java)

                if (sdp != null && type != null) {
                    val sessionDescription = SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type), sdp
                    )
                    peerConnection.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d("WebRTC", "Answer set as remote description")
                        }

                        override fun onSetFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sessionDescription)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForCalleeCandidates() {
        db.child("rooms").child(roomId).child("calleeCandidates")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val candidate = IceCandidate(
                        snapshot.child("sdpMid").getValue(String::class.java),
                        snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0,
                        snapshot.child("candidate").getValue(String::class.java)
                    )
                    Log.d("WebRTC", "Adding ICE Candidate: $candidate")
                    peerConnection.addIceCandidate(candidate)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun joinCall() {
        isCaller = false
        db.child("rooms").child(roomId).child("offer")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val type = snapshot.child("type").getValue(String::class.java)
                    val sdp = snapshot.child("sdp").getValue(String::class.java)

                    if (sdp != null && type != null) {
                        val sessionDescription = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type),
                            sdp
                        )

                        peerConnection.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d("WebRTC", "Offer set as remote description")
                                createAnswer()
                            }

                            override fun onSetFailure(p0: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, sessionDescription)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun createAnswer() {
        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            // Send answer to Firebase
                            db.child("rooms").child(roomId).child("answer").setValue(
                                mapOf(
                                    "type" to desc.type.canonicalForm(),
                                    "sdp" to desc.description
                                )
                            )
                            Log.d("WebRTC", "Answer created and sent.")
                        }

                        override fun onSetFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }
            }

            override fun onCreateFailure(reason: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, sdpConstraints)
    }

    private fun listenForCallerCandidates() {
        db.child("rooms").child(roomId).child("callerCandidates")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val candidate = IceCandidate(
                        snapshot.child("sdpMid").getValue(String::class.java),
                        snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0,
                        snapshot.child("candidate").getValue(String::class.java)
                    )
                    peerConnection.addIceCandidate(candidate)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }


}
