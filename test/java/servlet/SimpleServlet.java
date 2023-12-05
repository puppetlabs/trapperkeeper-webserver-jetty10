package servlet;

import java.io.IOException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SimpleServlet extends HttpServlet {
  private String body;
  private String contentType;

  private Boolean bodyAsOutputStream;


  public SimpleServlet(String body, String contentType, Boolean bodyAsOutputStream) {
    this.body = body;
    this.contentType = contentType;
    this.bodyAsOutputStream = bodyAsOutputStream;
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType(this.contentType);
    response.setStatus(HttpServletResponse.SC_OK);
    String pathInfo = request.getPathInfo();

    if (pathInfo != null && pathInfo.length() > 1 && pathInfo.charAt(0) == '/')
    {
      if (this.bodyAsOutputStream) {
        byte[] bytes = body.getBytes("UTF8");
        response.setContentLength(bytes.length);
        try (ServletOutputStream out = response.getOutputStream()) {
          out.write(bytes);
        }
      } else {
        response.getWriter().print(this.getInitParameter(pathInfo.substring(1)));
      }
    }
    else
    {
      if (this.bodyAsOutputStream) {
        byte[] bytes = body.getBytes("UTF8");
        response.setContentLength(bytes.length);
        try (ServletOutputStream out =response.getOutputStream()) {
          out.write(bytes);
        }
      } else {
        response.getWriter().print(body);
      }
    }
  }
}

