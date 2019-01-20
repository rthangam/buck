// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/remoteexecution/proto/metadata.proto

package com.facebook.buck.remoteexecution.proto;

@javax.annotation.Generated(value="protoc", comments="annotations:RemoteExecutionMetadataProto.java.pb.meta")
public final class RemoteExecutionMetadataProto {
  private RemoteExecutionMetadataProto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_facebook_remote_execution_TraceInfo_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_facebook_remote_execution_TraceInfo_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_facebook_remote_execution_RESessionID_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_facebook_remote_execution_RESessionID_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_facebook_remote_execution_BuckInfo_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_facebook_remote_execution_BuckInfo_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_facebook_remote_execution_CreatorInfo_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_facebook_remote_execution_CreatorInfo_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_facebook_remote_execution_ExecutionEngineInfo_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_facebook_remote_execution_ExecutionEngineInfo_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_facebook_remote_execution_WorkerInfo_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_facebook_remote_execution_WorkerInfo_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_facebook_remote_execution_RemoteExecutionMetadata_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_facebook_remote_execution_RemoteExecutionMetadata_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n:src/com/facebook/buck/remoteexecution/" +
      "proto/metadata.proto\022\031facebook.remote_ex" +
      "ecution\".\n\tTraceInfo\022\020\n\010trace_id\030\001 \001(\t\022\017" +
      "\n\007edge_id\030\002 \001(\t\"\031\n\013RESessionID\022\n\n\002id\030\001 \001" +
      "(\t\"\034\n\010BuckInfo\022\020\n\010build_id\030\001 \001(\t\"4\n\013Crea" +
      "torInfo\022\020\n\010username\030\001 \001(\t\022\023\n\013client_type" +
      "\030\002 \001(\t\"\'\n\023ExecutionEngineInfo\022\020\n\010hostnam" +
      "e\030\001 \001(\t\"6\n\nWorkerInfo\022\020\n\010hostname\030\001 \001(\t\022" +
      "\026\n\016execution_path\030\002 \001(\t\"\211\003\n\027RemoteExecut" +
      "ionMetadata\022=\n\rre_session_id\030\001 \001(\0132&.fac" +
      "ebook.remote_execution.RESessionID\0226\n\tbu" +
      "ck_info\030\002 \001(\0132#.facebook.remote_executio" +
      "n.BuckInfo\0228\n\ntrace_info\030\003 \001(\0132$.faceboo" +
      "k.remote_execution.TraceInfo\022<\n\014creator_" +
      "info\030\004 \001(\0132&.facebook.remote_execution.C" +
      "reatorInfo\022C\n\013engine_info\030\005 \001(\0132..facebo" +
      "ok.remote_execution.ExecutionEngineInfo\022" +
      ":\n\013worker_info\030\006 \001(\0132%.facebook.remote_e" +
      "xecution.WorkerInfoBI\n\'com.facebook.buck" +
      ".remoteexecution.protoB\034RemoteExecutionM" +
      "etadataProtoP\001b\006proto3"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
        new com.google.protobuf.Descriptors.FileDescriptor.    InternalDescriptorAssigner() {
          public com.google.protobuf.ExtensionRegistry assignDescriptors(
              com.google.protobuf.Descriptors.FileDescriptor root) {
            descriptor = root;
            return null;
          }
        };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        }, assigner);
    internal_static_facebook_remote_execution_TraceInfo_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_facebook_remote_execution_TraceInfo_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_facebook_remote_execution_TraceInfo_descriptor,
        new java.lang.String[] { "TraceId", "EdgeId", });
    internal_static_facebook_remote_execution_RESessionID_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_facebook_remote_execution_RESessionID_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_facebook_remote_execution_RESessionID_descriptor,
        new java.lang.String[] { "Id", });
    internal_static_facebook_remote_execution_BuckInfo_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_facebook_remote_execution_BuckInfo_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_facebook_remote_execution_BuckInfo_descriptor,
        new java.lang.String[] { "BuildId", });
    internal_static_facebook_remote_execution_CreatorInfo_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_facebook_remote_execution_CreatorInfo_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_facebook_remote_execution_CreatorInfo_descriptor,
        new java.lang.String[] { "Username", "ClientType", });
    internal_static_facebook_remote_execution_ExecutionEngineInfo_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_facebook_remote_execution_ExecutionEngineInfo_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_facebook_remote_execution_ExecutionEngineInfo_descriptor,
        new java.lang.String[] { "Hostname", });
    internal_static_facebook_remote_execution_WorkerInfo_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_facebook_remote_execution_WorkerInfo_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_facebook_remote_execution_WorkerInfo_descriptor,
        new java.lang.String[] { "Hostname", "ExecutionPath", });
    internal_static_facebook_remote_execution_RemoteExecutionMetadata_descriptor =
      getDescriptor().getMessageTypes().get(6);
    internal_static_facebook_remote_execution_RemoteExecutionMetadata_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_facebook_remote_execution_RemoteExecutionMetadata_descriptor,
        new java.lang.String[] { "ReSessionId", "BuckInfo", "TraceInfo", "CreatorInfo", "EngineInfo", "WorkerInfo", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
