/**
 * Autogenerated by Thrift Compiler (0.11.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.11.0)")
public class StoreBuildGraphRequest implements org.apache.thrift.TBase<StoreBuildGraphRequest, StoreBuildGraphRequest._Fields>, java.io.Serializable, Cloneable, Comparable<StoreBuildGraphRequest> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("StoreBuildGraphRequest");

  private static final org.apache.thrift.protocol.TField STAMPEDE_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("stampedeId", org.apache.thrift.protocol.TType.STRUCT, (short)1);
  private static final org.apache.thrift.protocol.TField BUILD_GRAPH_FIELD_DESC = new org.apache.thrift.protocol.TField("buildGraph", org.apache.thrift.protocol.TType.STRING, (short)2);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new StoreBuildGraphRequestStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new StoreBuildGraphRequestTupleSchemeFactory();

  public StampedeId stampedeId; // optional
  public java.nio.ByteBuffer buildGraph; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    STAMPEDE_ID((short)1, "stampedeId"),
    BUILD_GRAPH((short)2, "buildGraph");

    private static final java.util.Map<java.lang.String, _Fields> byName = new java.util.HashMap<java.lang.String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // STAMPEDE_ID
          return STAMPEDE_ID;
        case 2: // BUILD_GRAPH
          return BUILD_GRAPH;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new java.lang.IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(java.lang.String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final java.lang.String _fieldName;

    _Fields(short thriftId, java.lang.String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public java.lang.String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final _Fields optionals[] = {_Fields.STAMPEDE_ID,_Fields.BUILD_GRAPH};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.STAMPEDE_ID, new org.apache.thrift.meta_data.FieldMetaData("stampedeId", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, StampedeId.class)));
    tmpMap.put(_Fields.BUILD_GRAPH, new org.apache.thrift.meta_data.FieldMetaData("buildGraph", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING        , true)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(StoreBuildGraphRequest.class, metaDataMap);
  }

  public StoreBuildGraphRequest() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public StoreBuildGraphRequest(StoreBuildGraphRequest other) {
    if (other.isSetStampedeId()) {
      this.stampedeId = new StampedeId(other.stampedeId);
    }
    if (other.isSetBuildGraph()) {
      this.buildGraph = org.apache.thrift.TBaseHelper.copyBinary(other.buildGraph);
    }
  }

  public StoreBuildGraphRequest deepCopy() {
    return new StoreBuildGraphRequest(this);
  }

  @Override
  public void clear() {
    this.stampedeId = null;
    this.buildGraph = null;
  }

  public StampedeId getStampedeId() {
    return this.stampedeId;
  }

  public StoreBuildGraphRequest setStampedeId(StampedeId stampedeId) {
    this.stampedeId = stampedeId;
    return this;
  }

  public void unsetStampedeId() {
    this.stampedeId = null;
  }

  /** Returns true if field stampedeId is set (has been assigned a value) and false otherwise */
  public boolean isSetStampedeId() {
    return this.stampedeId != null;
  }

  public void setStampedeIdIsSet(boolean value) {
    if (!value) {
      this.stampedeId = null;
    }
  }

  public byte[] getBuildGraph() {
    setBuildGraph(org.apache.thrift.TBaseHelper.rightSize(buildGraph));
    return buildGraph == null ? null : buildGraph.array();
  }

  public java.nio.ByteBuffer bufferForBuildGraph() {
    return org.apache.thrift.TBaseHelper.copyBinary(buildGraph);
  }

  public StoreBuildGraphRequest setBuildGraph(byte[] buildGraph) {
    this.buildGraph = buildGraph == null ? (java.nio.ByteBuffer)null : java.nio.ByteBuffer.wrap(buildGraph.clone());
    return this;
  }

  public StoreBuildGraphRequest setBuildGraph(java.nio.ByteBuffer buildGraph) {
    this.buildGraph = org.apache.thrift.TBaseHelper.copyBinary(buildGraph);
    return this;
  }

  public void unsetBuildGraph() {
    this.buildGraph = null;
  }

  /** Returns true if field buildGraph is set (has been assigned a value) and false otherwise */
  public boolean isSetBuildGraph() {
    return this.buildGraph != null;
  }

  public void setBuildGraphIsSet(boolean value) {
    if (!value) {
      this.buildGraph = null;
    }
  }

  public void setFieldValue(_Fields field, java.lang.Object value) {
    switch (field) {
    case STAMPEDE_ID:
      if (value == null) {
        unsetStampedeId();
      } else {
        setStampedeId((StampedeId)value);
      }
      break;

    case BUILD_GRAPH:
      if (value == null) {
        unsetBuildGraph();
      } else {
        if (value instanceof byte[]) {
          setBuildGraph((byte[])value);
        } else {
          setBuildGraph((java.nio.ByteBuffer)value);
        }
      }
      break;

    }
  }

  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case STAMPEDE_ID:
      return getStampedeId();

    case BUILD_GRAPH:
      return getBuildGraph();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case STAMPEDE_ID:
      return isSetStampedeId();
    case BUILD_GRAPH:
      return isSetBuildGraph();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof StoreBuildGraphRequest)
      return this.equals((StoreBuildGraphRequest)that);
    return false;
  }

  public boolean equals(StoreBuildGraphRequest that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_stampedeId = true && this.isSetStampedeId();
    boolean that_present_stampedeId = true && that.isSetStampedeId();
    if (this_present_stampedeId || that_present_stampedeId) {
      if (!(this_present_stampedeId && that_present_stampedeId))
        return false;
      if (!this.stampedeId.equals(that.stampedeId))
        return false;
    }

    boolean this_present_buildGraph = true && this.isSetBuildGraph();
    boolean that_present_buildGraph = true && that.isSetBuildGraph();
    if (this_present_buildGraph || that_present_buildGraph) {
      if (!(this_present_buildGraph && that_present_buildGraph))
        return false;
      if (!this.buildGraph.equals(that.buildGraph))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetStampedeId()) ? 131071 : 524287);
    if (isSetStampedeId())
      hashCode = hashCode * 8191 + stampedeId.hashCode();

    hashCode = hashCode * 8191 + ((isSetBuildGraph()) ? 131071 : 524287);
    if (isSetBuildGraph())
      hashCode = hashCode * 8191 + buildGraph.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(StoreBuildGraphRequest other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetStampedeId()).compareTo(other.isSetStampedeId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetStampedeId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.stampedeId, other.stampedeId);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetBuildGraph()).compareTo(other.isSetBuildGraph());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetBuildGraph()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.buildGraph, other.buildGraph);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    scheme(iprot).read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    scheme(oprot).write(oprot, this);
  }

  @Override
  public java.lang.String toString() {
    java.lang.StringBuilder sb = new java.lang.StringBuilder("StoreBuildGraphRequest(");
    boolean first = true;

    if (isSetStampedeId()) {
      sb.append("stampedeId:");
      if (this.stampedeId == null) {
        sb.append("null");
      } else {
        sb.append(this.stampedeId);
      }
      first = false;
    }
    if (isSetBuildGraph()) {
      if (!first) sb.append(", ");
      sb.append("buildGraph:");
      if (this.buildGraph == null) {
        sb.append("null");
      } else {
        org.apache.thrift.TBaseHelper.toString(this.buildGraph, sb);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (stampedeId != null) {
      stampedeId.validate();
    }
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, java.lang.ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class StoreBuildGraphRequestStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public StoreBuildGraphRequestStandardScheme getScheme() {
      return new StoreBuildGraphRequestStandardScheme();
    }
  }

  private static class StoreBuildGraphRequestStandardScheme extends org.apache.thrift.scheme.StandardScheme<StoreBuildGraphRequest> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, StoreBuildGraphRequest struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // STAMPEDE_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.stampedeId = new StampedeId();
              struct.stampedeId.read(iprot);
              struct.setStampedeIdIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // BUILD_GRAPH
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.buildGraph = iprot.readBinary();
              struct.setBuildGraphIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, StoreBuildGraphRequest struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.stampedeId != null) {
        if (struct.isSetStampedeId()) {
          oprot.writeFieldBegin(STAMPEDE_ID_FIELD_DESC);
          struct.stampedeId.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      if (struct.buildGraph != null) {
        if (struct.isSetBuildGraph()) {
          oprot.writeFieldBegin(BUILD_GRAPH_FIELD_DESC);
          oprot.writeBinary(struct.buildGraph);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class StoreBuildGraphRequestTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public StoreBuildGraphRequestTupleScheme getScheme() {
      return new StoreBuildGraphRequestTupleScheme();
    }
  }

  private static class StoreBuildGraphRequestTupleScheme extends org.apache.thrift.scheme.TupleScheme<StoreBuildGraphRequest> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, StoreBuildGraphRequest struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetStampedeId()) {
        optionals.set(0);
      }
      if (struct.isSetBuildGraph()) {
        optionals.set(1);
      }
      oprot.writeBitSet(optionals, 2);
      if (struct.isSetStampedeId()) {
        struct.stampedeId.write(oprot);
      }
      if (struct.isSetBuildGraph()) {
        oprot.writeBinary(struct.buildGraph);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, StoreBuildGraphRequest struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(2);
      if (incoming.get(0)) {
        struct.stampedeId = new StampedeId();
        struct.stampedeId.read(iprot);
        struct.setStampedeIdIsSet(true);
      }
      if (incoming.get(1)) {
        struct.buildGraph = iprot.readBinary();
        struct.setBuildGraphIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

