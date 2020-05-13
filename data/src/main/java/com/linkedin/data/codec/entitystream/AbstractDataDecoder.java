/*
   Copyright (c) 2018 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.linkedin.data.codec.entitystream;

import com.linkedin.data.ByteString;
import com.linkedin.data.Data;
import com.linkedin.data.DataComplex;
import com.linkedin.data.DataList;
import com.linkedin.data.DataMap;
import com.linkedin.data.DataMapBuilder;
import com.linkedin.data.collections.CheckedUtil;
import com.linkedin.entitystream.ReadHandle;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.linkedin.data.Data.Token.*;

/**
 * A decoder for a {@link DataComplex} object implemented as a
 * {@link com.linkedin.entitystream.Reader} reading from an {@link com.linkedin.entitystream.EntityStream} of
 * ByteString. The implementation is backed by a non blocking {@link com.linkedin.data.Data.DataParser}
 * because the raw bytes are pushed to the decoder, it keeps the partially built data structure in a stack.
 * It is not thread safe. Caller must ensure thread safety.
 *
 * @author kramgopa, xma, amgupta1
 */
abstract class AbstractDataDecoder<T extends DataComplex> implements DataDecoder<T>
{
  private static final EnumSet<Data.Token> SIMPLE_VALUE =
      EnumSet.of(STRING, INTEGER, LONG, FLOAT, DOUBLE, BOOL_TRUE, BOOL_FALSE, NULL);
  private static final EnumSet<Data.Token> FIELD_NAME = EnumSet.of(STRING);
  private static final EnumSet<Data.Token> VALUE = EnumSet.of(START_OBJECT, START_ARRAY);
  private static final EnumSet<Data.Token> NEXT_OBJECT_FIELD = EnumSet.of(END_OBJECT);
  private static final EnumSet<Data.Token> NEXT_ARRAY_ITEM = EnumSet.of(END_ARRAY);

  protected static final EnumSet<Data.Token> NONE = EnumSet.noneOf(Data.Token.class);
  protected static final EnumSet<Data.Token> START_TOKENS = EnumSet.of(Data.Token.START_OBJECT, Data.Token.START_ARRAY);
  protected static final EnumSet<Data.Token> START_ARRAY_TOKEN = EnumSet.of(Data.Token.START_ARRAY);
  protected static final EnumSet<Data.Token> START_OBJECT_TOKEN = EnumSet.of(Data.Token.START_OBJECT);

  static
  {
    VALUE.addAll(SIMPLE_VALUE);
    NEXT_OBJECT_FIELD.addAll(FIELD_NAME);
    NEXT_ARRAY_ITEM.addAll(VALUE);
  }

  private CompletableFuture<T> _completable;
  private T _result;
  private ReadHandle _readHandle;
  private Data.DataParser _parser;

  private Deque<DataComplex> _stack;
  private Deque<String> _currFieldStack;
  private String _currField;
  private DataMapBuilder _currDataMapBuilder;
  private boolean _isCurrList;
  private ByteString _currentChunk;
  private int _currentChunkIndex = -1;

  protected EnumSet<Data.Token> _expectedTokens;

  protected AbstractDataDecoder(EnumSet<Data.Token> expectedTokens)
  {
    _completable = new CompletableFuture<>();
    _result = null;
    _stack = new ArrayDeque<>();
    _currFieldStack = new ArrayDeque<>();
    _currDataMapBuilder = new DataMapBuilder();
    _expectedTokens = expectedTokens;
  }

  protected AbstractDataDecoder()
  {
    this(START_TOKENS);
  }

  @Override
  public void onInit(ReadHandle rh)
  {
    _readHandle = rh;

    try
    {
      _parser = createDataParser();
    }
    catch (IOException e)
    {
      handleException(e);
    }

    _readHandle.request(1);
  }

  /**
   * Interface to create non blocking data object parser that process different kind of event/read operations.
   * Method can throw IOException
   */
  protected abstract Data.DataParser createDataParser() throws IOException;

  @Override
  public void onDataAvailable(ByteString data)
  {
    // Process chunk incrementally without copying the data in the interest of performance.
    _currentChunk = data;
    _currentChunkIndex = 0;

    processCurrentChunk();
  }

  private void readNextChunk()
  {
    if (_currentChunkIndex == -1)
    {
      _readHandle.request(1);
      return;
    }

    processCurrentChunk();
  }

  private void processCurrentChunk()
  {
    try
    {
      _currentChunkIndex = _currentChunk.feed(_parser, _currentChunkIndex);
      processTokens();
    }
    catch (IOException e)
    {
      handleException(e);
    }
  }

  private void processTokens()
  {
    try
    {
      Data.Token token;
      while ((token = _parser.nextToken()) != null)
      {
        switch (token)
        {
          case START_OBJECT:
            validate(START_OBJECT);
            // If we are already filling out a DataMap, we cannot reuse _currDataMapBuilder and thus
            // need to create a new one.
            if (_currDataMapBuilder.inUse())
            {
              _currDataMapBuilder = new DataMapBuilder();
            }
            _currDataMapBuilder.setInUse(true);
            push(_currDataMapBuilder, false);
            break;
          case END_OBJECT:
            validate(END_OBJECT);
            pop();
            break;
          case START_ARRAY:
            validate(START_ARRAY);
            push(new DataList(), true);
            break;
          case END_ARRAY:
            validate(END_ARRAY);
            pop();
            break;
          case STRING:
            validate(STRING);
            if (!_isCurrList && _currField == null) {
              _currField = _parser.getString();
              _expectedTokens = VALUE;
            } else {
              addValue(_parser.getString());
            }
            break;
          case INTEGER:
            addValue(_parser.getIntValue());
            break;
          case LONG:
            addValue(_parser.getLongValue());
            break;
          case FLOAT:
            addValue(_parser.getFloatValue());
            break;
          case DOUBLE:
            addValue(_parser.getDoubleValue());
            break;
          case BOOL_TRUE:
            validate(BOOL_TRUE);
            addValue(Boolean.TRUE);
            break;
          case BOOL_FALSE:
            validate(BOOL_FALSE);
            addValue(Boolean.FALSE);
            break;
          case NULL:
            validate(NULL);
            addValue(Data.NULL);
            break;
          case NOT_AVAILABLE:
            readNextChunk();
            return;
          default:
            handleException(new Exception("Unexpected token " + token + " from data parser"));
        }
      }
    }
    catch (IOException e)
    {
      handleException(e);
    }
  }

  protected final void validate(Data.Token token)
  {
    if (!_expectedTokens.contains(token))
    {
      handleException(new Exception("Expecting " + _expectedTokens + " but got " + token));
    }
  }

  private void push(DataComplex dataComplex, boolean isList)
  {
    if (!(_isCurrList || _stack.isEmpty()))
    {
      _currFieldStack.push(_currField);
      _currField = null;
    }
    _stack.push(dataComplex);
    _isCurrList = isList;
    updateExpected();
  }

  @SuppressWarnings("unchecked")
  private void pop()
  {
    // The stack should never be empty because of token validation.
    assert !_stack.isEmpty() : "Trying to pop empty stack";

    DataComplex tmp = _stack.pop();

    if (tmp instanceof DataMapBuilder)
    {
      tmp = ((DataMapBuilder) tmp).convertToDataMap();
    }

    if (_stack.isEmpty())
    {
      _result = (T) tmp;
      // No more tokens is expected.
      _expectedTokens = NONE;
    }
    else
    {
      _isCurrList = _stack.peek() instanceof DataList;
      if (!_isCurrList)
      {
        _currField = _currFieldStack.pop();
      }
      addValue(tmp);
      updateExpected();
    }
  }

  protected void addValue(Object value)
  {
    if (!_stack.isEmpty())
    {
      DataComplex currItem = _stack.peek();
      if (_isCurrList)
      {
        CheckedUtil.addWithoutChecking((DataList) currItem, value);
      }
      else
      {
        if (currItem instanceof DataMapBuilder)
        {
          DataMapBuilder dataMapBuilder = (DataMapBuilder) currItem;
          if (dataMapBuilder.smallHashMapThresholdReached())
          {
            _stack.pop();
            DataMap dataMap = dataMapBuilder.convertToDataMap();
            _stack.push(dataMap);
            CheckedUtil.putWithoutChecking(dataMap, _currField, value);
          }
          else
          {
            dataMapBuilder.addKVPair(_currField, value);
          }
        }
        else
        {
          CheckedUtil.putWithoutChecking((DataMap) currItem, _currField, value);
        }
        _currField = null;
      }

      updateExpected();
    }
  }

  protected void updateExpected()
  {
    _expectedTokens = _isCurrList ? NEXT_ARRAY_ITEM : NEXT_OBJECT_FIELD;
  }

  @Override
  public void onDone()
  {
    // We must signal to the parser the end of the input and pull any remaining token, even if it's unexpected.
    _parser.endOfInput();
    processTokens();

    if (_stack.isEmpty())
    {
      _completable.complete(_result);
    }
    else
    {
      handleException(new Exception("Unexpected end of source"));
    }
  }

  @Override
  public void onError(Throwable e)
  {
    _completable.completeExceptionally(e);
  }

  @Override
  public CompletionStage<T> getResult()
  {
    return _completable;
  }

  protected void handleException(Throwable e)
  {
    _readHandle.cancel();
    _completable.completeExceptionally(e);
  }
}
