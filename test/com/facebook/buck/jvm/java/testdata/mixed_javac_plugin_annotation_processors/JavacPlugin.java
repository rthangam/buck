package com.example;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;

public class JavacPlugin implements Plugin {

  @Override
  public String getName() {
    return "MyPlugin";
  }

  @Override
  public void init(JavacTask task, String... args) {
    new JpUtil().doStuff();
  }
}
