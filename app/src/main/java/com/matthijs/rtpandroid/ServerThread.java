package com.matthijs.rtpandroid;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by matthijs on 25-5-16.
 */
public class ServerThread extends Thread {
    //RTP variables:
    //----------------
    private DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
    private DatagramPacket senddp; //UDP packet containing the video frames

    private InetAddress ClientIPAddr; //Client IP address
    private int RTP_dest_port = 0; //destination port for RTP packets  (given by the RTSP Client)


    //Video variables:
    //----------------
    int imagenb = 0; //image nb of the image currently transmitted
    VideoStream video; //VideoStream object used to access video frames
    static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video
    static int FRAME_PERIOD = 50; //Frame period of the video to stream, in ms
    static int VIDEO_LENGTH = 500; //length of the video in frames

    Timer timer; //timer used to send the images at the video frame rate
    byte[] buf; //buffer used to store the images to send to the client

    //RTSP variables
    //----------------
    //rtsp states
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    //rtsp message types
    final static int SETUP = 3;
    final static int PLAY = 4;
    final static int PAUSE = 5;
    final static int TEARDOWN = 6;

    private int state; //RTSP Server state == INIT or READY or PLAY
    private Socket RTSPsocket; //socket used to send/receive RTSP messages
    //input and output stream filters
    private BufferedReader RTSPBufferedReader;
    private BufferedWriter RTSPBufferedWriter;
    private String VideoFileName; //video file requested from the client
    private int RTSP_ID = 123456; //ID of the RTSP session
    private int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session
    final static String CRLF = "\r\n";

    private Context context;

    public ServerThread(Socket socket, Context context) {
        this.RTSPsocket = socket;
        this.context = context;
        this.ClientIPAddr = RTSPsocket.getInetAddress();
    }

    @Override
    public void run() {
        try {
            //Initiate RTSPstate
            state = INIT;

            //Set input and output stream filters:
            RTSPBufferedReader = new BufferedReader(new InputStreamReader(RTSPsocket.getInputStream()));
            RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(RTSPsocket.getOutputStream()));

            //Wait for the SETUP message from the client
            int request_type;
            boolean done = false;
            while (!done) {
                request_type = parse_RTSP_request(); //blocking

                if (request_type == SETUP) {
                    done = true;
                    buf = new byte[50000];
                    //update RTSP state
                    state = READY;
                    System.out.println("New RTSP state: READY");

                    //Send response
                    send_RTSP_response();

                    //init the VideoStream object:
                    video = new VideoStream(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + Environment.DIRECTORY_MOVIES + "/" + VideoFileName);

                    //init RTP socket
                    RTPsocket = new DatagramSocket();
                }
            }

            //loop to handle RTSP requests
            while (true) {
                //parse the request
                request_type = parse_RTSP_request(); //blocking

                if ((request_type == PLAY) && (state == READY)) {
                    //send back response
                    send_RTSP_response();

                    //Setup and start timer
                    timer = new Timer();
                    timer.scheduleAtFixedRate(new TimerTask() {
                        @Override
                        public void run() {
                            sendPacket();
                        }
                    }, 0, FRAME_PERIOD);

                    //update state
                    state = PLAYING;
                    System.out.println("New RTSP state: PLAYING");
                } else if ((request_type == PAUSE) && (state == PLAYING)) {
                    //send back response
                    send_RTSP_response();
                    //stop timer
                    timer.cancel();

                    //update state
                    state = READY;
                    System.out.println("New RTSP state: READY");
                } else if (request_type == TEARDOWN) {
                    //send back response
                    send_RTSP_response();
                    //stop timer
                    timer.cancel();
                    //close sockets
                    RTSPsocket.close();
                    RTPsocket.close();

                    //System.exit(0);
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //------------------------
    //Handler for timer
    //------------------------
    public void sendPacket() {

        //if the current image nb is less than the length of the video
        if (imagenb < VIDEO_LENGTH)
        {
            //update current imagenb
            imagenb++;

            try {
                //get next frame to send from the video, as well as its size
                int image_length = video.getnextframe(buf);
                System.out.println("Image length: " +image_length);
                //Builds an RTPpacket object containing the frame
                RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb*FRAME_PERIOD, buf, image_length);

                //get to total length of the full rtp packet to send
                int packet_length = rtp_packet.getlength();

                //retrieve the packet bitstream and store it in an array of bytes
                byte[] packet_bits = new byte[packet_length];
                rtp_packet.getpacket(packet_bits);

                //send the packet as a DatagramPacket over the UDP socket
                senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port);
                System.out.print("RTP_dest_port =" + RTP_dest_port +"\n" );

                RTPsocket.send(senddp);

                //System.out.println("Send frame #"+imagenb);
                //print the header bitstream
                rtp_packet.printheader();

            }
            catch(Exception ex)
            {
                ex.printStackTrace();
            }
        }
        else
        {
            //if we have reached the end of the video file, stop the timer
            timer.cancel();
        }
    }

    //------------------------------------
    //Parse RTSP Request
    //------------------------------------
    private int parse_RTSP_request()
    {
        int request_type = -1;
        try{
            //parse request line and extract the request_type:
            String RequestLine = RTSPBufferedReader.readLine();
            //System.out.println("RTSP Server - Received from Client:");
            System.out.println(RequestLine);

            StringTokenizer tokens = new StringTokenizer(RequestLine);
            String request_type_string = tokens.nextToken();

            //convert to request_type structure:
            if ((new String(request_type_string)).compareTo("SETUP") == 0)
                request_type = SETUP;
            else if ((new String(request_type_string)).compareTo("PLAY") == 0)
                request_type = PLAY;
            else if ((new String(request_type_string)).compareTo("PAUSE") == 0)
                request_type = PAUSE;
            else if ((new String(request_type_string)).compareTo("TEARDOWN") == 0)
                request_type = TEARDOWN;

            if (request_type == SETUP)
            {
                //extract VideoFileName from RequestLine
                VideoFileName = tokens.nextToken();
            }

            //parse the SeqNumLine and extract CSeq field
            String SeqNumLine = RTSPBufferedReader.readLine();
            System.out.println(SeqNumLine);
            tokens = new StringTokenizer(SeqNumLine);
            tokens.nextToken();
            RTSPSeqNb = Integer.parseInt(tokens.nextToken());

            //get LastLine
            String LastLine = RTSPBufferedReader.readLine();
            System.out.println(LastLine);

            if (request_type == SETUP)
            {
                //extract RTP_dest_port from LastLine
                tokens = new StringTokenizer(LastLine);
                for (int i=0; i<3; i++)
                    tokens.nextToken(); //skip unused stuff
                RTP_dest_port = Integer.parseInt(tokens.nextToken());
            }
            //else LastLine will be the SessionId line ... do not check for now.
        }
        catch(Exception ex)
        {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
        return(request_type);
    }

    //------------------------------------
    //Send RTSP Response
    //------------------------------------
    private void send_RTSP_response()
    {
        try{
            RTSPBufferedWriter.write("RTSP/1.0 200 OK"+CRLF);
            RTSPBufferedWriter.write("CSeq: "+RTSPSeqNb+CRLF);
            RTSPBufferedWriter.write("Session: "+RTSP_ID+CRLF);
            RTSPBufferedWriter.flush();
            //System.out.println("RTSP Server - Sent response to Client.");
        }
        catch(Exception ex)
        {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
    }
}
