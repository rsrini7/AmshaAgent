package com.amsha.tools.builtin.config;

import com.amsha.tools.builtin.BraveWebSearchTool;
import com.amsha.tools.builtin.CheckListTool;
import com.amsha.tools.builtin.GithubTool;
import com.amsha.tools.builtin.HostExecTool;
import com.amsha.tools.builtin.HostJavaCompiler;
import com.amsha.tools.builtin.LocalSocksProxy;
import com.amsha.tools.builtin.ManageScheduleTool;
import com.amsha.tools.builtin.SandboxExecTool;
import com.amsha.tools.builtin.SandboxVerificationGate;
import com.amsha.tools.builtin.ScheduleTaskTool;
import com.amsha.tools.builtin.SerperWebSearchTool;
import com.amsha.tools.builtin.SmartWebFetchTool;
import com.amsha.tools.builtin.ToolSearchIndex;
import com.amsha.tools.builtin.memory.ArchivalMemoryInsertTool;
import com.amsha.tools.builtin.memory.ArchivalMemorySearchTool;
import com.amsha.tools.builtin.memory.CoreMemoryAppendTool;
import com.amsha.tools.builtin.memory.CoreMemoryReadTool;
import com.amsha.tools.builtin.memory.CoreMemoryReplaceTool;
import com.amsha.tools.builtin.memory.RecallMemorySearchTool;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            ArchivalMemoryInsertTool.class,
            ArchivalMemorySearchTool.class,
            BraveWebSearchTool.class,
            CheckListTool.class,
            CoreMemoryAppendTool.class,
            CoreMemoryReadTool.class,
            CoreMemoryReplaceTool.class,
            GithubTool.class,
            HostExecTool.class,
            HostJavaCompiler.class,
            LocalSocksProxy.class,
            ManageScheduleTool.class,
            RecallMemorySearchTool.class,
            SandboxExecTool.class,
            SandboxVerificationGate.class,
            ScheduleTaskTool.class,
            SerperWebSearchTool.class,
            SmartWebFetchTool.class,
            ToolSearchIndex.class,
        },
        classNames = {
            "org.apache.lucene.internal.tests.TestSecrets",
            "org.apache.lucene.index.ConcurrentMergeScheduler",
            "org.apache.lucene.index.SegmentReader",
            "org.apache.lucene.index.IndexWriter",
            "org.apache.lucene.store.FilterIndexInput"
        })
public class ToolsBuiltinReflectionConfig {}
