package com.zml.servlet;

import com.zml.annotation.MyAutowired;
import com.zml.annotation.MyController;
import com.zml.annotation.MyRequestMapping;
import com.zml.annotation.MyService;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author: maylor
 * @date: 2021/5/14 15:49
 * @description:
 */
public class MyDispatcherServlet extends HttpServlet {

	private Properties         properties     = new Properties();
	// 缓存扫描到的类的全限定类名
	private List<String>       classNames     = new ArrayList<>();
	// ioc容器
	private Map<String,Object> ioc            = new HashMap<String,Object>();
	// handlerMapping
	//private Map<String,Method> handlerMapping = now HashMap<>(); // 存储url和Method之间的映射关系
	private List<Handler>      handlerMapping = new ArrayList<>();

	@Override public void init(ServletConfig config) throws ServletException {
		// 1 加载配置⽂件 springmvc.properties
		String contextConfigLocation = config.getInitParameter("contextConfigLocation");
		doLoadConfig(contextConfigLocation);
		// 2 扫描相关的类，扫描注解
		doScan(properties.getProperty("scanPackage"));
		// 3 初始化bean对象（实现ioc容器，基于注解）
		doInstance();
		// 4 实现依赖注⼊
		doAutoWired();
		// 5 构造⼀个HandlerMapping处理器映射器，将配置好的url和Method建⽴映射关系
		initHandlerMapping();
		System.out.println("lagou mvc 初始化完成....");
		// 等待请求进⼊，处理请求
	}

	// 加载配置⽂件
	private void doLoadConfig(String contextConfigLocation) {
		InputStream resourceAsStream =
				this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
		try {
			properties.load(resourceAsStream);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// 扫描类
	// scanPackage: com.lagou.demo package----> 磁盘上的⽂件夹（File）com/lagou/demo
	private void doScan(String scanPackage) {
		String scanPackagePath =
				Thread.currentThread().getContextClassLoader().getResource("").getPath() +
						scanPackage.replaceAll("\\.", "/");
		File pack = new File(scanPackagePath);
		File[] files = pack.listFiles();
		for (File file : files) {
			if (file.isDirectory()) { // ⼦package
				// 递归
				doScan(scanPackage + "." + file.getName()); //com.lagou.demo.controller
			} else if (file.getName().endsWith(".class")) {
				String className = scanPackage + "." +
						file.getName().replaceAll(".class", "");
				classNames.add(className);
			}
		}
	}

	// ⾸字⺟⼩写⽅法
	public String lowerFirst(String str) {
		char[] chars = str.toCharArray();
		if ('A' <= chars[0] && chars[0] <= 'Z') {
			chars[0] += 32;
		}
		return String.valueOf(chars);
	}

	// ioc容器
	// 基于classNames缓存的类的全限定类名，以及反射技术，完成对象创建和管理
	private void doInstance() {
		if (classNames.size() == 0)
			return;
		try {
			for (int i = 0; i < classNames.size(); i++) {
				String className = classNames.get(i); //com.lagou.demo.controller.DemoController
				// 反射
				Class<?> aClass = Class.forName(className);
				// 区分controller，区分service'
				if (aClass.isAnnotationPresent(MyController.class)) {
					// controller的id此处不做过多处理，不取value了，就拿类的⾸字⺟⼩写作为id，保存到ioc中
					String simpleName = aClass.getSimpleName();//DemoController
					String lowerFirstSimpleName = lowerFirst(simpleName);
					// demoController
					Object o = aClass.newInstance();
					ioc.put(lowerFirstSimpleName, o);
				} else if (aClass.isAnnotationPresent(MyService.class)) {
					MyService annotation =
							aClass.getAnnotation(MyService.class);
					//获取注解value值
					String beanName = annotation.value();
					// 如果指定了id，就以指定的为准
					if (!"".equals(beanName.trim())) {
						ioc.put(beanName, aClass.newInstance());
					} else {
						// 如果没有指定，就以类名⾸字⺟⼩写
						beanName = lowerFirst(aClass.getSimpleName());
						ioc.put(beanName, aClass.newInstance());
					}
					// service层往往是有接⼝的，⾯向接⼝开发，此时再以接⼝名为id，放⼊⼀份对象到ioc中，便于后期根据接⼝类型注⼊
					Class<?>[] interfaces = aClass.getInterfaces();
					for (int j = 0; j < interfaces.length; j++) {
						Class<?> anInterface = interfaces[j];
						// 以接⼝的全限定类名作为id放⼊
						ioc.put(anInterface.getName(), aClass.newInstance());
					}
				} else {
					continue;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// 实现依赖注⼊
	private void doAutoWired() {
		if (ioc.isEmpty()) {
			return;
		}
		// 有对象，再进⾏依赖注⼊处理
		// 遍历ioc中所有对象，查看对象中的字段，是否有@MyAutowired注解，如果有需要维护依赖注⼊关系
		for (Map.Entry<String,Object> entry : ioc.entrySet()) {
			// 获取bean对象中的字段信息
			Field[] declaredFields =
					entry.getValue().getClass().getDeclaredFields();
			// 遍历判断处理
			for (int i = 0; i < declaredFields.length; i++) {
				Field declaredField = declaredFields[i]; //@MyAutowired private IDemoService demoService;
				if (!declaredField.isAnnotationPresent(MyAutowired.class)) {
					continue;
				}
				// 有该注解
				MyAutowired annotation =
						declaredField.getAnnotation(MyAutowired.class);
				String beanName = annotation.value(); // 需要注⼊的bean的id
				if ("".equals(beanName.trim())) {
					// 没有配置具体的bean id，那就需要根据当前字段类型注⼊（接⼝注⼊） IDemoService
					beanName = declaredField.getType().getName();
				}
				// 开启赋值
				declaredField.setAccessible(true);
				try {
					declaredField.set(entry.getValue(), ioc.get(beanName));
				} catch (IllegalAccessException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/*
	 构造⼀个HandlerMapping处理器映射器
	 最关键的环节
	 ⽬的：将url和method建⽴关联
	 */
	private void initHandlerMapping() {
		if (ioc.isEmpty()) {
			return;
		}
		for (Map.Entry<String,Object> entry : ioc.entrySet()) {
			// 获取ioc中当前遍历的对象的class类型
			Class<?> aClass = entry.getValue().getClass();
			if (!aClass.isAnnotationPresent(MyController.class)) {
				continue;
			}
			String baseUrl = "";
			if (aClass.isAnnotationPresent(MyRequestMapping.class)) {
				MyRequestMapping annotation =
						aClass.getAnnotation(MyRequestMapping.class);
				baseUrl = annotation.value(); // 等同于/demo
			}
			// 获取⽅法
			Method[] methods = aClass.getMethods();
			for (int i = 0; i < methods.length; i++) {
				Method method = methods[i];
				// ⽅法没有标识MyRequestMapping，就不处理
				if (!method.isAnnotationPresent(MyRequestMapping.class)) {
					continue;
				}
				// 如果标识，就处理
				MyRequestMapping annotation = method.getAnnotation(MyRequestMapping.class);
				String methodUrl = annotation.value(); // /query
				String url = baseUrl + methodUrl; // 计算出来的url/demo / query
				// 把method所有信息及url封装为⼀个Handler
				Handler handler = new Handler(entry.getValue(), method,
						Pattern.compile(url));
				// 计算⽅法的参数位置信息 // query(HttpServletRequestrequest, HttpServletResponse response, String name)
				Parameter[] parameters = method.getParameters();
				for (int j = 0; j < parameters.length; j++) {
					Parameter parameter = parameters[j];
					if (parameter.getType() == HttpServletRequest.class ||
							parameter.getType() == HttpServletResponse.class) {
						// 如果是request和response对象，那么参数名称写HttpServletRequest和HttpServletResponse
						handler.getParamIndexMapping().put(parameter.getType().getSimpleName(), j);
					} else {
						handler.getParamIndexMapping().put(parameter.getName(), j); // <name,2>
					}
				}
				// 建⽴url和method之间的映射关系（map缓存起来）
				handlerMapping.add(handler);
			}
		}
	}

	@Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doPost(req, resp);
	}

	@Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// 获取uri
		// String requestURI = req.getRequestURI();
		// Method method = handlerMapping.get(requestURI);// 获取到⼀个反射的⽅法
		// 反射调⽤，需要传⼊对象，需要传⼊参数，此处⽆法完成调⽤，没有把对象缓存起来，也没有参数！！！！改造initHandlerMapping();
		// method.invoke() //
		// 根据uri获取到能够处理当前请求的hanlder（从handlermapping中（list））
		Handler handler = getHandler(req);
		if (handler == null) {
			resp.getWriter().write("404 not found");
			return;
		}
		// 参数绑定
		// 获取所有参数类型数组，这个数组的⻓度就是我们最后要传⼊的args数组的⻓度
		Class<?>[] parameterTypes = handler.getMethod().getParameterTypes();
		// 根据上述数组⻓度创建⼀个新的数组（参数数组，是要传⼊反射调⽤的）
		Object[] paraValues = new Object[parameterTypes.length];
		// 以下就是为了向参数数组中塞值，⽽且还得保证参数的顺序和⽅法中形参顺序⼀致
		Map<String,String[]> parameterMap = req.getParameterMap();
		// 遍历request中所有参数 （填充除了request，response之外的参数）
		for (Map.Entry<String,String[]> param : parameterMap.entrySet()) {
			// name=1&name=2 name [1,2]
			String value = StringUtils.join(param.getValue(), ","); // 如 同 1,2
			// 如果参数和⽅法中的参数匹配上了，填充数据
			if (!handler.getParamIndexMapping().containsKey(param.getKey())) {
				continue;
			}
			// ⽅法形参确实有该参数，找到它的索引位置，对应的把参数值放⼊paraValues
			Integer index = handler.getParamIndexMapping().get(param.getKey());//name在第 2 个位置
			paraValues[index] = value; // 把前台传递过来的参数值填充到对应的位置去
		}
		int requestIndex = handler.getParamIndexMapping().get(HttpServletRequest.class.getSimpleName()); // 0
		paraValues[requestIndex] = req;
		int responseIndex = handler.getParamIndexMapping().get(HttpServletResponse.class.getSimpleName()); // 1
		paraValues[responseIndex] = resp;
		// 最终调⽤handler的method属性
		try {
			handler.getMethod().invoke(handler.getController(), paraValues);
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}
	}

	private Handler getHandler(HttpServletRequest req) {
		if (handlerMapping.isEmpty()) {
			return null;
		}
		String url = req.getRequestURI();
		for (Handler handler : handlerMapping) {
			Matcher matcher = handler.getPattern().matcher(url);
			if (!matcher.matches()) {
				continue;
			}
			return handler;
		}
		return null;
	}
}
