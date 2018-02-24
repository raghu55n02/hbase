/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.procedure2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ProcedureProtos.StateMachineProcedureData;

/**
 * Procedure described by a series of steps.
 *
 * <p>The procedure implementor must have an enum of 'states', describing
 * the various step of the procedure.
 * Once the procedure is running, the procedure-framework will call executeFromState()
 * using the 'state' provided by the user. The first call to executeFromState()
 * will be performed with 'state = null'. The implementor can jump between
 * states using setNextState(MyStateEnum.ordinal()).
 * The rollback will call rollbackState() for each state that was executed, in reverse order.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public abstract class StateMachineProcedure<TEnvironment, TState>
    extends Procedure<TEnvironment> {
  private static final Logger LOG = LoggerFactory.getLogger(StateMachineProcedure.class);

  private static final int EOF_STATE = Integer.MIN_VALUE;

  private final AtomicBoolean aborted = new AtomicBoolean(false);

  private Flow stateFlow = Flow.HAS_MORE_STATE;
  private int stateCount = 0;
  private int[] states = null;

  private List<Procedure<TEnvironment>> subProcList = null;

  protected final int getCycles() {
    return cycles;
  }

  /**
   * Cycles on same state. Good for figuring if we are stuck.
   */
  private int cycles = 0;

  /**
   * Ordinal of the previous state. So we can tell if we are progressing or not.
   */
  private int previousState;

  protected enum Flow {
    HAS_MORE_STATE,
    NO_MORE_STATE,
  }

  /**
   * called to perform a single step of the specified 'state' of the procedure
   * @param state state to execute
   * @return Flow.NO_MORE_STATE if the procedure is completed,
   *         Flow.HAS_MORE_STATE if there is another step.
   */
  protected abstract Flow executeFromState(TEnvironment env, TState state)
  throws ProcedureSuspendedException, ProcedureYieldException, InterruptedException;

  /**
   * called to perform the rollback of the specified state
   * @param state state to rollback
   * @throws IOException temporary failure, the rollback will retry later
   */
  protected abstract void rollbackState(TEnvironment env, TState state)
    throws IOException, InterruptedException;

  /**
   * Convert an ordinal (or state id) to an Enum (or more descriptive) state object.
   * @param stateId the ordinal() of the state enum (or state id)
   * @return the state enum object
   */
  protected abstract TState getState(int stateId);

  /**
   * Convert the Enum (or more descriptive) state object to an ordinal (or state id).
   * @param state the state enum object
   * @return stateId the ordinal() of the state enum (or state id)
   */
  protected abstract int getStateId(TState state);

  /**
   * Return the initial state object that will be used for the first call to executeFromState().
   * @return the initial state enum object
   */
  protected abstract TState getInitialState();

  /**
   * Set the next state for the procedure.
   * @param state the state enum object
   */
  protected void setNextState(final TState state) {
    setNextState(getStateId(state));
    failIfAborted();
  }

  /**
   * By default, the executor will try ro run all the steps of the procedure start to finish.
   * Return true to make the executor yield between execution steps to
   * give other procedures time to run their steps.
   * @param state the state we are going to execute next.
   * @return Return true if the executor should yield before the execution of the specified step.
   *         Defaults to return false.
   */
  protected boolean isYieldBeforeExecuteFromState(TEnvironment env, TState state) {
    return false;
  }

  /**
   * Add a child procedure to execute
   * @param subProcedure the child procedure
   */
  protected void addChildProcedure(Procedure<TEnvironment>... subProcedure) {
    if (subProcedure == null) return;
    final int len = subProcedure.length;
    if (len == 0) return;
    if (subProcList == null) {
      subProcList = new ArrayList<>(len);
    }
    for (int i = 0; i < len; ++i) {
      Procedure<TEnvironment> proc = subProcedure[i];
      if (!proc.hasOwner()) proc.setOwner(getOwner());
      subProcList.add(proc);
    }
  }

  @Override
  protected Procedure[] execute(final TEnvironment env)
  throws ProcedureSuspendedException, ProcedureYieldException, InterruptedException {
    updateTimestamp();
    try {
      failIfAborted();

      if (!hasMoreState() || isFailed()) return null;
      TState state = getCurrentState();
      if (stateCount == 0) {
        setNextState(getStateId(state));
      }

      if (LOG.isTraceEnabled()) {
        LOG.trace(state  + " " + this + "; cycles=" + this.cycles);
      }
      // Keep running count of cycles
      if (getStateId(state) != this.previousState) {
        this.previousState = getStateId(state);
        this.cycles = 0;
      } else {
        this.cycles++;
      }

      LOG.trace("{}", toString());
      stateFlow = executeFromState(env, state);
      if (!hasMoreState()) setNextState(EOF_STATE);
      if (subProcList != null && !subProcList.isEmpty()) {
        Procedure[] subProcedures = subProcList.toArray(new Procedure[subProcList.size()]);
        subProcList = null;
        return subProcedures;
      }
      return (isWaiting() || isFailed() || !hasMoreState()) ? null : new Procedure[] {this};
    } finally {
      updateTimestamp();
    }
  }

  @Override
  protected void rollback(final TEnvironment env)
      throws IOException, InterruptedException {
    if (isEofState()) stateCount--;
    try {
      updateTimestamp();
      rollbackState(env, getCurrentState());
      stateCount--;
    } finally {
      updateTimestamp();
    }
  }

  private boolean isEofState() {
    return stateCount > 0 && states[stateCount-1] == EOF_STATE;
  }

  @Override
  protected boolean abort(final TEnvironment env) {
    LOG.debug("Abort requested for {}", this);
    if (hasMoreState()) {
      aborted.set(true);
      return true;
    }
    LOG.debug("Ignoring abort request on {}", this);
    return false;
  }

  /**
   * If procedure has more states then abort it otherwise procedure is finished and abort can be
   * ignored.
   */
  protected final void failIfAborted() {
    if (aborted.get()) {
      if (hasMoreState()) {
        setAbortFailure(getClass().getSimpleName(), "abort requested");
      } else {
        LOG.warn("Ignoring abort request on state='" + getCurrentState() + "' for " + this);
      }
    }
  }

  /**
   * Used by the default implementation of abort() to know if the current state can be aborted
   * and rollback can be triggered.
   */
  protected boolean isRollbackSupported(final TState state) {
    return false;
  }

  @Override
  protected boolean isYieldAfterExecutionStep(final TEnvironment env) {
    return isYieldBeforeExecuteFromState(env, getCurrentState());
  }

  private boolean hasMoreState() {
    return stateFlow != Flow.NO_MORE_STATE;
  }

  protected TState getCurrentState() {
    return stateCount > 0 ? getState(states[stateCount-1]) : getInitialState();
  }

  /**
   * Set the next state for the procedure.
   * @param stateId the ordinal() of the state enum (or state id)
   */
  private void setNextState(final int stateId) {
    if (states == null || states.length == stateCount) {
      int newCapacity = stateCount + 8;
      if (states != null) {
        states = Arrays.copyOf(states, newCapacity);
      } else {
        states = new int[newCapacity];
      }
    }
    states[stateCount++] = stateId;
  }

  @Override
  protected void toStringState(StringBuilder builder) {
    super.toStringState(builder);
    if (!isFinished() && !isEofState() && getCurrentState() != null) {
      builder.append(":").append(getCurrentState());
    }
  }

  @Override
  protected void serializeStateData(ProcedureStateSerializer serializer)
      throws IOException {
    StateMachineProcedureData.Builder data = StateMachineProcedureData.newBuilder();
    for (int i = 0; i < stateCount; ++i) {
      data.addState(states[i]);
    }
    serializer.serialize(data.build());
  }

  @Override
  protected void deserializeStateData(ProcedureStateSerializer serializer)
      throws IOException {
    StateMachineProcedureData data = serializer.deserialize(StateMachineProcedureData.class);
    stateCount = data.getStateCount();
    if (stateCount > 0) {
      states = new int[stateCount];
      for (int i = 0; i < stateCount; ++i) {
        states[i] = data.getState(i);
      }
      if (isEofState()) {
        stateFlow = Flow.NO_MORE_STATE;
      }
    } else {
      states = null;
    }
  }
}
