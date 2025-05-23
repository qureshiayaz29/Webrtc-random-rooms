package com.example.videostreaming

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
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
    private lateinit var joinCallButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var loadingOverlay: View
    private lateinit var waitingText: TextView
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var localVideoTrack: VideoTrack
    private lateinit var localAudioTrack: AudioTrack
    private lateinit var videoCapturer: VideoCapturer
    private lateinit var localStream: MediaStream
    private lateinit var peerConnection: PeerConnection
    private lateinit var db: DatabaseReference
    private var roomId: String? = null
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
        joinCallButton = findViewById(R.id.joinCallButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        waitingText = findViewById(R.id.waitingText)

        // Initially hide loading overlay
        loadingOverlay.visibility = View.GONE

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

        joinCallButton.setOnClickListener {
            findOrCreateRoom()
        }

        disconnectButton.setOnClickListener {
            disconnectCall()
        }
    }

    private fun findOrCreateRoom() {
        loadingOverlay.visibility = View.VISIBLE
        waitingText.text = "Finding someone to chat with..."
        joinCallButton.visibility = View.GONE

        // Query for rooms in waiting state
        db.child("rooms")
            .orderByChild("status")
            .equalTo("waiting")
            .limitToFirst(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // Found a waiting room, join it
                    val roomEntry = snapshot.children.first()
                    roomId = roomEntry.key
                    Log.d("WebRTC", "Found waiting room: $roomId")
                    
                    // Update room status to matched
                    db.child("rooms").child(roomId!!).child("status").setValue("matched")
                        .addOnSuccessListener {
                            // Join the room
                            isCaller = false
                            joinExistingRoom(roomId!!)
                        }
                } else {
                    // No waiting rooms found, create new room
                    createNewRoom()
                }
            }
            .addOnFailureListener { e ->
                Log.e("WebRTC", "Error finding room: ${e.message}")
                loadingOverlay.visibility = View.GONE
                joinCallButton.visibility = View.VISIBLE
                Toast.makeText(this, "Failed to connect", Toast.LENGTH_SHORT).show()
            }
    }

    private fun createNewRoom() {
        roomId = db.child("rooms").push().key
        if (roomId == null) {
            Log.e("WebRTC", "Couldn't generate room key")
            return
        }

        val roomData = mapOf(
            "created" to ServerValue.TIMESTAMP,
            "status" to "waiting"
        )

        db.child("rooms").child(roomId!!).setValue(roomData)
            .addOnSuccessListener {
                Log.d("WebRTC", "Created new room: $roomId")
                isCaller = true
                waitingText.text = "Waiting for someone to join..."
                
                // Start listening for room status changes
                listenForRoomMatch()
                
                // Create WebRTC offer
                startCall()
            }
            .addOnFailureListener { e ->
                Log.e("WebRTC", "Failed to create room: ${e.message}")
                loadingOverlay.visibility = View.GONE
                joinCallButton.visibility = View.VISIBLE
            }
    }

    private fun joinExistingRoom(roomId: String) {
        Log.d("WebRTC", "Joining room: $roomId")
        waitingText.text = "Connecting to peer..."
        
        // Listen for offer and join
        db.child("rooms").child(roomId).child("offer")
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val type = snapshot.child("type").getValue(String::class.java)
                    val sdp = snapshot.child("sdp").getValue(String::class.java)
                    if (type != null && sdp != null) {
                        joinCall()
                        listenForCallerCandidates()
                    } else {
                        Log.e("WebRTC", "Invalid offer data")
                        handleConnectionError()
                    }
                } else {
                    Log.e("WebRTC", "No offer found")
                    handleConnectionError()
                }
            }
            .addOnFailureListener { e ->
                Log.e("WebRTC", "Failed to get offer: ${e.message}")
                handleConnectionError()
            }
    }

    private fun listenForRoomMatch() {
        roomId?.let { currentRoomId ->
            db.child("rooms").child(currentRoomId).child("status")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val status = snapshot.getValue(String::class.java)
                        when (status) {
                            "matched" -> {
                                if (isCaller) {
                                    waitingText.text = "Someone joined! Connecting..."
                                    listenForAnswer()
                                    listenForCalleeCandidates()
                                }
                            }
                            "disconnected" -> {
                                if (this@MainActivity::peerConnection.isInitialized) {
                                    disconnectCall()
                                }
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("WebRTC", "Room status listener cancelled: ${error.message}")
                    }
                })
        }
    }

    private fun handleConnectionError() {
        loadingOverlay.visibility = View.GONE
        joinCallButton.visibility = View.VISIBLE
        roomId?.let { currentRoomId ->
            db.child("rooms").child(currentRoomId).removeValue()
        }
        roomId = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up room if we're the creator and it's still in waiting state
        roomId?.let { currentRoomId ->
            db.child("rooms").child(currentRoomId).get().addOnSuccessListener { snapshot ->
                if (snapshot.exists() && snapshot.child("status").getValue(String::class.java) == "waiting") {
                    db.child("rooms").child(currentRoomId).removeValue()
                }
            }
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
                        db.child("rooms").child(roomId!!).child(candidateType).push()
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
                    runOnUiThread {
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                // Hide loading overlay and show disconnect button
                                loadingOverlay.visibility = View.GONE
                                disconnectButton.visibility = View.VISIBLE
                                joinCallButton.visibility = View.GONE
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED,
                            PeerConnection.PeerConnectionState.FAILED,
                            PeerConnection.PeerConnectionState.CLOSED -> {
                                // Show join button and hide others
                                loadingOverlay.visibility = View.GONE
                                disconnectButton.visibility = View.GONE
                                joinCallButton.visibility = View.VISIBLE
                            }
                            PeerConnection.PeerConnectionState.CONNECTING -> {
                                // Show loading overlay with connecting message
                                loadingOverlay.visibility = View.VISIBLE
                                waitingText.text = "Connecting..."
                                disconnectButton.visibility = View.GONE
                                joinCallButton.visibility = View.GONE
                            }
                            else -> { /* No changes needed for other states */ }
                        }
                    }
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                        listenForCallStatus()
                    }
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
        Log.d("WebRTC", "Starting call as caller")
        
        // Create room in Firebase
        val roomData = mapOf(
            "created" to System.currentTimeMillis(),
            "status" to "waiting"
        )
        
        db.child("rooms").child(roomId!!).setValue(roomData)
            .addOnSuccessListener {
                Log.d("WebRTC", "Room created successfully")
                // Create and send offer
                createOffer()
                // Start listening for callee candidates
                listenForCalleeCandidates()
            }
            .addOnFailureListener { e ->
                Log.e("WebRTC", "Failed to create room: ${e.message}")
                loadingOverlay.visibility = View.GONE
                joinCallButton.visibility = View.VISIBLE
            }
    }

    private fun createOffer() {
        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d("WebRTC", "Offer created successfully")
                if (desc != null) {
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d("WebRTC", "Local description set. Sending offer to Firebase.")
                            db.child("rooms").child(roomId!!).child("offer").setValue(
                                mapOf(
                                    "type" to desc.type.canonicalForm(),
                                    "sdp" to desc.description
                                )
                            ).addOnSuccessListener {
                                Log.d("WebRTC", "Offer saved to Firebase successfully")
                                listenForAnswer()
                            }.addOnFailureListener { e ->
                                Log.e("WebRTC", "Failed to save offer: ${e.message}")
                            }
                        }
                        override fun onSetFailure(p0: String?) {
                            Log.e("WebRTC", "Failed to set local description: $p0")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }
            }
            override fun onCreateFailure(reason: String?) {
                Log.e("WebRTC", "Failed to create offer: $reason")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, sdpConstraints)
    }

    private fun listenForAnswer() {
        db.child("rooms").child(roomId!!).child("answer").addValueEventListener(object :
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
        db.child("rooms").child(roomId!!).child("calleeCandidates")
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
        Log.d("WebRTC", "Joining call as callee")
        loadingOverlay.visibility = View.VISIBLE
        waitingText.text = "Connecting to call..."
        joinCallButton.visibility = View.GONE

        // Start listening for ICE candidates from caller
        listenForCallerCandidates()
        
        db.child("rooms").child(roomId!!).child("offer")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val type = snapshot.child("type").getValue(String::class.java)
                    val sdp = snapshot.child("sdp").getValue(String::class.java)

                    if (sdp != null && type != null) {
                        Log.d("WebRTC", "Got offer from Firebase, setting remote description")
                        val sessionDescription = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type),
                            sdp
                        )

                        peerConnection.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d("WebRTC", "Remote description set, creating answer")
                                createAnswer()
                            }
                            override fun onSetFailure(p0: String?) {
                                Log.e("WebRTC", "Failed to set remote description: $p0")
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, sessionDescription)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("WebRTC", "Failed to get offer: ${error.message}")
                    loadingOverlay.visibility = View.GONE
                    joinCallButton.visibility = View.VISIBLE
                }
            })
    }

    private fun createAnswer() {
        Log.d("WebRTC", "Creating answer")
        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    Log.d("WebRTC", "Answer created, setting local description")
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d("WebRTC", "Local description set, sending answer to Firebase")
                            db.child("rooms").child(roomId!!).child("answer").setValue(
                                mapOf(
                                    "type" to desc.type.canonicalForm(),
                                    "sdp" to desc.description
                                )
                            )
                        }
                        override fun onSetFailure(p0: String?) {
                            Log.e("WebRTC", "Failed to set local description: $p0")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }
            }
            override fun onCreateFailure(reason: String?) {
                Log.e("WebRTC", "Failed to create answer: $reason")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, sdpConstraints)
    }

    private fun listenForCallerCandidates() {
        db.child("rooms").child(roomId!!).child("callerCandidates")
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

    private fun disconnectCall() {
        // Notify other peer about disconnection
        if (::db.isInitialized) {
            db.child("rooms").child(roomId!!).child("status").setValue("disconnected")
        }
        
        // Clean up WebRTC resources
        peerConnection.close()
        videoCapturer.dispose()
        localView.release()
        remoteView.release()
        
        // Update UI
        loadingOverlay.visibility = View.GONE
        disconnectButton.visibility = View.GONE
        joinCallButton.visibility = View.VISIBLE
        
        // Show a toast message
        Toast.makeText(this, "Call ended", Toast.LENGTH_SHORT).show()
        
        // Optional: Finish the activity or reset the connection
        finish()
    }

    private fun listenForCallStatus() {
        db.child("rooms").child(roomId!!).child("status")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.getValue(String::class.java)
                    if (status == "disconnected") {
                        // Other peer has disconnected
                        Toast.makeText(this@MainActivity, "Call ended by other peer", Toast.LENGTH_SHORT).show()
                        disconnectCall()
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    override fun onBackPressed() {
        disconnectCall()
        super.onBackPressed()
    }
}
