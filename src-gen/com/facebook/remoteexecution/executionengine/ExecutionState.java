/**
 * Autogenerated by Thrift Compiler (0.11.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.remoteexecution.executionengine;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.11.0)")
public class ExecutionState implements org.apache.thrift.TBase<ExecutionState, ExecutionState._Fields>, java.io.Serializable, Cloneable, Comparable<ExecutionState> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("ExecutionState");

  private static final org.apache.thrift.protocol.TField EXECUTION_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("execution_id", org.apache.thrift.protocol.TType.STRING, (short)1);
  private static final org.apache.thrift.protocol.TField METADATA_FIELD_DESC = new org.apache.thrift.protocol.TField("metadata", org.apache.thrift.protocol.TType.STRUCT, (short)2);
  private static final org.apache.thrift.protocol.TField DONE_FIELD_DESC = new org.apache.thrift.protocol.TField("done", org.apache.thrift.protocol.TType.BOOL, (short)3);
  private static final org.apache.thrift.protocol.TField RESULT_FIELD_DESC = new org.apache.thrift.protocol.TField("result", org.apache.thrift.protocol.TType.STRUCT, (short)4);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new ExecutionStateStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new ExecutionStateTupleSchemeFactory();

  public java.lang.String execution_id; // required
  public ExecutionMetadata metadata; // required
  public boolean done; // required
  public ExecutionResult result; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    EXECUTION_ID((short)1, "execution_id"),
    METADATA((short)2, "metadata"),
    DONE((short)3, "done"),
    RESULT((short)4, "result");

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
        case 1: // EXECUTION_ID
          return EXECUTION_ID;
        case 2: // METADATA
          return METADATA;
        case 3: // DONE
          return DONE;
        case 4: // RESULT
          return RESULT;
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
  private static final int __DONE_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.RESULT};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.EXECUTION_ID, new org.apache.thrift.meta_data.FieldMetaData("execution_id", org.apache.thrift.TFieldRequirementType.DEFAULT, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.METADATA, new org.apache.thrift.meta_data.FieldMetaData("metadata", org.apache.thrift.TFieldRequirementType.DEFAULT, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, ExecutionMetadata.class)));
    tmpMap.put(_Fields.DONE, new org.apache.thrift.meta_data.FieldMetaData("done", org.apache.thrift.TFieldRequirementType.DEFAULT, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.BOOL)));
    tmpMap.put(_Fields.RESULT, new org.apache.thrift.meta_data.FieldMetaData("result", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, ExecutionResult.class)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(ExecutionState.class, metaDataMap);
  }

  public ExecutionState() {
  }

  public ExecutionState(
    java.lang.String execution_id,
    ExecutionMetadata metadata,
    boolean done)
  {
    this();
    this.execution_id = execution_id;
    this.metadata = metadata;
    this.done = done;
    setDoneIsSet(true);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public ExecutionState(ExecutionState other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetExecution_id()) {
      this.execution_id = other.execution_id;
    }
    if (other.isSetMetadata()) {
      this.metadata = new ExecutionMetadata(other.metadata);
    }
    this.done = other.done;
    if (other.isSetResult()) {
      this.result = new ExecutionResult(other.result);
    }
  }

  public ExecutionState deepCopy() {
    return new ExecutionState(this);
  }

  @Override
  public void clear() {
    this.execution_id = null;
    this.metadata = null;
    setDoneIsSet(false);
    this.done = false;
    this.result = null;
  }

  public java.lang.String getExecution_id() {
    return this.execution_id;
  }

  public ExecutionState setExecution_id(java.lang.String execution_id) {
    this.execution_id = execution_id;
    return this;
  }

  public void unsetExecution_id() {
    this.execution_id = null;
  }

  /** Returns true if field execution_id is set (has been assigned a value) and false otherwise */
  public boolean isSetExecution_id() {
    return this.execution_id != null;
  }

  public void setExecution_idIsSet(boolean value) {
    if (!value) {
      this.execution_id = null;
    }
  }

  public ExecutionMetadata getMetadata() {
    return this.metadata;
  }

  public ExecutionState setMetadata(ExecutionMetadata metadata) {
    this.metadata = metadata;
    return this;
  }

  public void unsetMetadata() {
    this.metadata = null;
  }

  /** Returns true if field metadata is set (has been assigned a value) and false otherwise */
  public boolean isSetMetadata() {
    return this.metadata != null;
  }

  public void setMetadataIsSet(boolean value) {
    if (!value) {
      this.metadata = null;
    }
  }

  public boolean isDone() {
    return this.done;
  }

  public ExecutionState setDone(boolean done) {
    this.done = done;
    setDoneIsSet(true);
    return this;
  }

  public void unsetDone() {
    __isset_bitfield = org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __DONE_ISSET_ID);
  }

  /** Returns true if field done is set (has been assigned a value) and false otherwise */
  public boolean isSetDone() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __DONE_ISSET_ID);
  }

  public void setDoneIsSet(boolean value) {
    __isset_bitfield = org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __DONE_ISSET_ID, value);
  }

  public ExecutionResult getResult() {
    return this.result;
  }

  public ExecutionState setResult(ExecutionResult result) {
    this.result = result;
    return this;
  }

  public void unsetResult() {
    this.result = null;
  }

  /** Returns true if field result is set (has been assigned a value) and false otherwise */
  public boolean isSetResult() {
    return this.result != null;
  }

  public void setResultIsSet(boolean value) {
    if (!value) {
      this.result = null;
    }
  }

  public void setFieldValue(_Fields field, java.lang.Object value) {
    switch (field) {
    case EXECUTION_ID:
      if (value == null) {
        unsetExecution_id();
      } else {
        setExecution_id((java.lang.String)value);
      }
      break;

    case METADATA:
      if (value == null) {
        unsetMetadata();
      } else {
        setMetadata((ExecutionMetadata)value);
      }
      break;

    case DONE:
      if (value == null) {
        unsetDone();
      } else {
        setDone((java.lang.Boolean)value);
      }
      break;

    case RESULT:
      if (value == null) {
        unsetResult();
      } else {
        setResult((ExecutionResult)value);
      }
      break;

    }
  }

  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case EXECUTION_ID:
      return getExecution_id();

    case METADATA:
      return getMetadata();

    case DONE:
      return isDone();

    case RESULT:
      return getResult();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case EXECUTION_ID:
      return isSetExecution_id();
    case METADATA:
      return isSetMetadata();
    case DONE:
      return isSetDone();
    case RESULT:
      return isSetResult();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof ExecutionState)
      return this.equals((ExecutionState)that);
    return false;
  }

  public boolean equals(ExecutionState that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_execution_id = true && this.isSetExecution_id();
    boolean that_present_execution_id = true && that.isSetExecution_id();
    if (this_present_execution_id || that_present_execution_id) {
      if (!(this_present_execution_id && that_present_execution_id))
        return false;
      if (!this.execution_id.equals(that.execution_id))
        return false;
    }

    boolean this_present_metadata = true && this.isSetMetadata();
    boolean that_present_metadata = true && that.isSetMetadata();
    if (this_present_metadata || that_present_metadata) {
      if (!(this_present_metadata && that_present_metadata))
        return false;
      if (!this.metadata.equals(that.metadata))
        return false;
    }

    boolean this_present_done = true;
    boolean that_present_done = true;
    if (this_present_done || that_present_done) {
      if (!(this_present_done && that_present_done))
        return false;
      if (this.done != that.done)
        return false;
    }

    boolean this_present_result = true && this.isSetResult();
    boolean that_present_result = true && that.isSetResult();
    if (this_present_result || that_present_result) {
      if (!(this_present_result && that_present_result))
        return false;
      if (!this.result.equals(that.result))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetExecution_id()) ? 131071 : 524287);
    if (isSetExecution_id())
      hashCode = hashCode * 8191 + execution_id.hashCode();

    hashCode = hashCode * 8191 + ((isSetMetadata()) ? 131071 : 524287);
    if (isSetMetadata())
      hashCode = hashCode * 8191 + metadata.hashCode();

    hashCode = hashCode * 8191 + ((done) ? 131071 : 524287);

    hashCode = hashCode * 8191 + ((isSetResult()) ? 131071 : 524287);
    if (isSetResult())
      hashCode = hashCode * 8191 + result.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(ExecutionState other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetExecution_id()).compareTo(other.isSetExecution_id());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetExecution_id()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.execution_id, other.execution_id);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetMetadata()).compareTo(other.isSetMetadata());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetMetadata()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.metadata, other.metadata);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetDone()).compareTo(other.isSetDone());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetDone()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.done, other.done);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetResult()).compareTo(other.isSetResult());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetResult()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.result, other.result);
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
    java.lang.StringBuilder sb = new java.lang.StringBuilder("ExecutionState(");
    boolean first = true;

    sb.append("execution_id:");
    if (this.execution_id == null) {
      sb.append("null");
    } else {
      sb.append(this.execution_id);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("metadata:");
    if (this.metadata == null) {
      sb.append("null");
    } else {
      sb.append(this.metadata);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("done:");
    sb.append(this.done);
    first = false;
    if (isSetResult()) {
      if (!first) sb.append(", ");
      sb.append("result:");
      if (this.result == null) {
        sb.append("null");
      } else {
        sb.append(this.result);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (metadata != null) {
      metadata.validate();
    }
    if (result != null) {
      result.validate();
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
      // it doesn't seem like you should have to do this, but java serialization is wacky, and doesn't call the default constructor.
      __isset_bitfield = 0;
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class ExecutionStateStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public ExecutionStateStandardScheme getScheme() {
      return new ExecutionStateStandardScheme();
    }
  }

  private static class ExecutionStateStandardScheme extends org.apache.thrift.scheme.StandardScheme<ExecutionState> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, ExecutionState struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // EXECUTION_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.execution_id = iprot.readString();
              struct.setExecution_idIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // METADATA
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.metadata = new ExecutionMetadata();
              struct.metadata.read(iprot);
              struct.setMetadataIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // DONE
            if (schemeField.type == org.apache.thrift.protocol.TType.BOOL) {
              struct.done = iprot.readBool();
              struct.setDoneIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 4: // RESULT
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.result = new ExecutionResult();
              struct.result.read(iprot);
              struct.setResultIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, ExecutionState struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.execution_id != null) {
        oprot.writeFieldBegin(EXECUTION_ID_FIELD_DESC);
        oprot.writeString(struct.execution_id);
        oprot.writeFieldEnd();
      }
      if (struct.metadata != null) {
        oprot.writeFieldBegin(METADATA_FIELD_DESC);
        struct.metadata.write(oprot);
        oprot.writeFieldEnd();
      }
      oprot.writeFieldBegin(DONE_FIELD_DESC);
      oprot.writeBool(struct.done);
      oprot.writeFieldEnd();
      if (struct.result != null) {
        if (struct.isSetResult()) {
          oprot.writeFieldBegin(RESULT_FIELD_DESC);
          struct.result.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class ExecutionStateTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public ExecutionStateTupleScheme getScheme() {
      return new ExecutionStateTupleScheme();
    }
  }

  private static class ExecutionStateTupleScheme extends org.apache.thrift.scheme.TupleScheme<ExecutionState> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, ExecutionState struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetExecution_id()) {
        optionals.set(0);
      }
      if (struct.isSetMetadata()) {
        optionals.set(1);
      }
      if (struct.isSetDone()) {
        optionals.set(2);
      }
      if (struct.isSetResult()) {
        optionals.set(3);
      }
      oprot.writeBitSet(optionals, 4);
      if (struct.isSetExecution_id()) {
        oprot.writeString(struct.execution_id);
      }
      if (struct.isSetMetadata()) {
        struct.metadata.write(oprot);
      }
      if (struct.isSetDone()) {
        oprot.writeBool(struct.done);
      }
      if (struct.isSetResult()) {
        struct.result.write(oprot);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, ExecutionState struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(4);
      if (incoming.get(0)) {
        struct.execution_id = iprot.readString();
        struct.setExecution_idIsSet(true);
      }
      if (incoming.get(1)) {
        struct.metadata = new ExecutionMetadata();
        struct.metadata.read(iprot);
        struct.setMetadataIsSet(true);
      }
      if (incoming.get(2)) {
        struct.done = iprot.readBool();
        struct.setDoneIsSet(true);
      }
      if (incoming.get(3)) {
        struct.result = new ExecutionResult();
        struct.result.read(iprot);
        struct.setResultIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}
