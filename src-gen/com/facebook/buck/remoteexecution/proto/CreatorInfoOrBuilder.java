// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/remoteexecution/proto/metadata.proto

package com.facebook.buck.remoteexecution.proto;

@javax.annotation.Generated(value="protoc", comments="annotations:CreatorInfoOrBuilder.java.pb.meta")
public interface CreatorInfoOrBuilder extends
    // @@protoc_insertion_point(interface_extends:facebook.remote_execution.CreatorInfo)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string username = 1;</code>
   */
  java.lang.String getUsername();
  /**
   * <code>string username = 1;</code>
   */
  com.google.protobuf.ByteString
      getUsernameBytes();

  /**
   * <pre>
   * Freeform string that a client (e.g. an IDE, CI) may set to identify itself.
   * </pre>
   *
   * <code>string client_type = 2;</code>
   */
  java.lang.String getClientType();
  /**
   * <pre>
   * Freeform string that a client (e.g. an IDE, CI) may set to identify itself.
   * </pre>
   *
   * <code>string client_type = 2;</code>
   */
  com.google.protobuf.ByteString
      getClientTypeBytes();
}
